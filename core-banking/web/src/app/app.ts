import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Apparence } from './core/apparence/apparence';
import { AppConfig } from './core/config/runtime-config';
import { CbButton, CbKbd } from './ui';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CbButton, CbKbd],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly config = inject(AppConfig);
  protected readonly apparence = inject(Apparence);
}
