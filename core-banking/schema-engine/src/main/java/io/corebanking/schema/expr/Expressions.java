package io.corebanking.schema.expr;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compilation des expressions de schema comptable.
 *
 * <p>Analyse descendante recursive, sans dependance et sans evaluation dynamique. Une expression
 * fautive est rejetee ici, au chargement du parametrage.
 */
public final class Expressions {

    /** Precision des divisions : tres au-dela de l'echelle de comptabilisation. */
    static final MathContext DIVISION = new MathContext(34, RoundingMode.HALF_EVEN);

    private Expressions() {}

    public static Expression parse(String source) {
        Parser parser = new Parser(source);
        Expression expression = parser.parseExpression();
        parser.expectEnd();
        return new Compiled(source, expression);
    }

    /** Expression compilee, porteuse de son texte d'origine. */
    private record Compiled(String text, Expression delegate) implements Expression {
        @Override public Object evaluate(EvaluationContext context) {
            return delegate.evaluate(context);
        }
        @Override public void collectVariables(Set<String> into) {
            delegate.collectVariables(into);
        }
        @Override public String source() { return text; }
        @Override public String toString() { return text; }
    }

    // ================================================================== lexique

    private enum Kind { NUMBER, IDENT, OPERATOR, LPAREN, RPAREN, COMMA, END }

    private record Token(Kind kind, String text, int position) {}

    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "true", "false");

    /**
     * Fonctions disponibles, et leur arite.
     *
     * <p>Le nom et le nombre d'arguments sont verifies <b>a l'analyse</b>, pas a l'evaluation. Une
     * fonction inexistante ou mal appelee doit faire echouer le deploiement du parametrage ; la
     * decouvrir au TFJ, sur une operation client, serait un incident de production evitable.
     */
    private static final java.util.Map<String, Integer> FUNCTIONS =
        java.util.Map.of("round", 2, "abs", 1, "min", 2, "max", 2);

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < source.length()
                       && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, source.substring(start, i), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < source.length()
                       && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_'
                           || source.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Kind.IDENT, source.substring(start, i), start));
            } else if (c == '(') {
                tokens.add(new Token(Kind.LPAREN, "(", i++));
            } else if (c == ')') {
                tokens.add(new Token(Kind.RPAREN, ")", i++));
            } else if (c == ',') {
                tokens.add(new Token(Kind.COMMA, ",", i++));
            } else {
                String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
                if (two.equals(">=") || two.equals("<=") || two.equals("==") || two.equals("!=")) {
                    tokens.add(new Token(Kind.OPERATOR, two, i));
                    i += 2;
                } else if ("+-*/><".indexOf(c) >= 0) {
                    tokens.add(new Token(Kind.OPERATOR, String.valueOf(c), i++));
                } else {
                    throw ExpressionException.at(source, i, "Caractere inattendu « " + c + " »");
                }
            }
        }
        tokens.add(new Token(Kind.END, "", source.length()));
        return tokens;
    }

    // ================================================================== analyse

    private static final class Parser {
        private final String source;
        private final List<Token> tokens;
        private int index;

        Parser(String source) {
            this.source = source;
            this.tokens = tokenize(source);
        }

        Expression parseExpression() {
            return parseOr();
        }

        void expectEnd() {
            if (peek().kind() != Kind.END) {
                throw ExpressionException.at(source, peek().position(),
                    "Element inattendu « " + peek().text() + " » apres la fin de l'expression");
            }
        }

        private Expression parseOr() {
            Expression left = parseAnd();
            while (matchKeyword("or")) {
                Expression right = parseAnd();
                left = new Logical(left, right, false);
            }
            return left;
        }

        private Expression parseAnd() {
            Expression left = parseNot();
            while (matchKeyword("and")) {
                Expression right = parseNot();
                left = new Logical(left, right, true);
            }
            return left;
        }

        private Expression parseNot() {
            if (matchKeyword("not")) {
                return new Negation(parseNot());
            }
            return parseComparison();
        }

        private Expression parseComparison() {
            Expression left = parseSum();
            Token token = peek();
            if (token.kind() == Kind.OPERATOR
                && Set.of(">", "<", ">=", "<=", "==", "!=").contains(token.text())) {
                next();
                return new Comparison(left, parseSum(), token.text());
            }
            return left;
        }

        private Expression parseSum() {
            Expression left = parseProduct();
            while (peek().kind() == Kind.OPERATOR
                   && (peek().text().equals("+") || peek().text().equals("-"))) {
                String operator = next().text();
                left = new Arithmetic(left, parseProduct(), operator);
            }
            return left;
        }

        private Expression parseProduct() {
            Expression left = parseUnary();
            while (peek().kind() == Kind.OPERATOR
                   && (peek().text().equals("*") || peek().text().equals("/"))) {
                String operator = next().text();
                left = new Arithmetic(left, parseUnary(), operator);
            }
            return left;
        }

        private Expression parseUnary() {
            if (peek().kind() == Kind.OPERATOR && peek().text().equals("-")) {
                next();
                return new Arithmetic(new Literal(BigDecimal.ZERO), parseUnary(), "-");
            }
            return parsePrimary();
        }

        private Expression parsePrimary() {
            Token token = next();
            switch (token.kind()) {
                case NUMBER:
                    return new Literal(new BigDecimal(token.text()));
                case LPAREN: {
                    Expression inner = parseExpression();
                    expect(Kind.RPAREN, ")");
                    return inner;
                }
                case IDENT: {
                    if (token.text().equals("true"))  return new Literal(Boolean.TRUE);
                    if (token.text().equals("false")) return new Literal(Boolean.FALSE);
                    if (KEYWORDS.contains(token.text())) {
                        throw ExpressionException.at(source, token.position(),
                            "Mot reserve « " + token.text() + " » employe comme variable");
                    }
                    if (peek().kind() == Kind.LPAREN) {
                        next();
                        List<Expression> arguments = new ArrayList<>();
                        if (peek().kind() != Kind.RPAREN) {
                            arguments.add(parseExpression());
                            while (peek().kind() == Kind.COMMA) {
                                next();
                                arguments.add(parseExpression());
                            }
                        }
                        expect(Kind.RPAREN, ")");
                        Integer arity = FUNCTIONS.get(token.text());
                        if (arity == null) {
                            throw ExpressionException.at(source, token.position(),
                                "Fonction inconnue « " + token.text() + " ». Disponibles : "
                                + FUNCTIONS.keySet().stream().sorted().toList()
                                + ". Le langage des schemas n'atteint rien hors de lui-meme.");
                        }
                        if (arguments.size() != arity) {
                            throw ExpressionException.at(source, token.position(),
                                "La fonction « " + token.text() + " » attend " + arity
                                + " argument(s), " + arguments.size() + " fourni(s)");
                        }
                        return new FunctionCall(token.text(), arguments, token.position(), source);
                    }
                    return new Variable(token.text());
                }
                default:
                    throw ExpressionException.at(source, token.position(),
                        "Element inattendu « " + token.text() + " »");
            }
        }

        private boolean matchKeyword(String keyword) {
            if (peek().kind() == Kind.IDENT && peek().text().equals(keyword)) {
                next();
                return true;
            }
            return false;
        }

        private void expect(Kind kind, String text) {
            if (peek().kind() != kind) {
                throw ExpressionException.at(source, peek().position(), "« " + text + " » attendu");
            }
            next();
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token next() {
            return tokens.get(index++);
        }
    }

    // ================================================================== noeuds

    private record Literal(Object value) implements Expression {
        @Override public Object evaluate(EvaluationContext context) { return value; }
        @Override public void collectVariables(Set<String> into) { }
    }

    private record Variable(String name) implements Expression {
        @Override public Object evaluate(EvaluationContext context) { return context.require(name); }
        @Override public void collectVariables(Set<String> into) { into.add(name); }
    }

    private record Arithmetic(Expression left, Expression right, String operator)
            implements Expression {
        @Override
        public Object evaluate(EvaluationContext context) {
            BigDecimal a = left.asNumber(context);
            BigDecimal b = right.asNumber(context);
            return switch (operator) {
                case "+" -> a.add(b);
                case "-" -> a.subtract(b);
                case "*" -> a.multiply(b);
                case "/" -> {
                    if (b.signum() == 0) {
                        throw new ExpressionException("Division par zero dans le schema comptable");
                    }
                    yield a.divide(b, DIVISION);
                }
                default -> throw new ExpressionException("Operateur inconnu : " + operator);
            };
        }

        @Override
        public void collectVariables(Set<String> into) {
            left.collectVariables(into);
            right.collectVariables(into);
        }
    }

    private record Comparison(Expression left, Expression right, String operator)
            implements Expression {
        @Override
        public Object evaluate(EvaluationContext context) {
            int comparison = left.asNumber(context).compareTo(right.asNumber(context));
            return switch (operator) {
                case ">"  -> comparison > 0;
                case ">=" -> comparison >= 0;
                case "<"  -> comparison < 0;
                case "<=" -> comparison <= 0;
                case "==" -> comparison == 0;
                case "!=" -> comparison != 0;
                default -> throw new ExpressionException("Comparateur inconnu : " + operator);
            };
        }

        @Override
        public void collectVariables(Set<String> into) {
            left.collectVariables(into);
            right.collectVariables(into);
        }
    }

    private record Logical(Expression left, Expression right, boolean conjunction)
            implements Expression {
        @Override
        public Object evaluate(EvaluationContext context) {
            boolean a = left.asBoolean(context);
            return conjunction ? a && right.asBoolean(context) : a || right.asBoolean(context);
        }

        @Override
        public void collectVariables(Set<String> into) {
            left.collectVariables(into);
            right.collectVariables(into);
        }
    }

    private record Negation(Expression inner) implements Expression {
        @Override
        public Object evaluate(EvaluationContext context) { return !inner.asBoolean(context); }
        @Override
        public void collectVariables(Set<String> into) { inner.collectVariables(into); }
    }

    private record FunctionCall(String name, List<Expression> arguments, int position, String source)
            implements Expression {
        @Override
        public Object evaluate(EvaluationContext context) {
            // Nom et arite ont ete verifies a l'analyse : ici, plus aucun cas d'erreur.
            return switch (name) {
                case "round" -> arguments.get(0).asNumber(context)
                    .setScale(arguments.get(1).asNumber(context).intValueExact(),
                              RoundingMode.HALF_EVEN);
                case "abs" -> arguments.get(0).asNumber(context).abs();
                case "min" -> arguments.get(0).asNumber(context)
                    .min(arguments.get(1).asNumber(context));
                default -> arguments.get(0).asNumber(context)
                    .max(arguments.get(1).asNumber(context));
            };
        }

        @Override
        public void collectVariables(Set<String> into) {
            arguments.forEach(argument -> argument.collectVariables(into));
        }
    }
}
