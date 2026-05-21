import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'auth',
    pathMatch: 'full',
  },
  {
    path: 'auth',
    loadComponent: () =>
      import('./features/auth/auth.component').then(m => m.AuthComponent),
  },
  {
    path: 'onboarding',
    loadComponent: () =>
      import('./features/onboarding/onboarding.component').then(m => m.OnboardingComponent),
  },
  {
    path: 'onboarding/guest',
    loadComponent: () =>
      import('./features/onboarding/onboarding.component').then(m => m.OnboardingComponent),
  },
  {
    path: 'analyzer',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/query-analyzer/query-analyzer.component').then(
        m => m.QueryAnalyzerComponent
      ),
  },
  {
    path: 'migration',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/migration/migration.component').then(
        m => m.MigrationComponent
      ),
  },
  {
    path: 'schema-explorer',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/schema-explorer/schema-explorer.component').then(
        m => m.SchemaExplorerComponent
      ),
  },
  {
    path: 'history',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/query-history/query-history.component').then(
        m => m.QueryHistoryComponent
      ),
  },
  {
    path: 'ai-suggestions',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/ai-suggestions/ai-suggestions.component').then(
        m => m.AiSuggestionsComponent
      ),
  },
  {
    path: 'settings',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/settings/settings.component').then(
        m => m.SettingsComponent
      ),
  },
  {
    path: '**',
    redirectTo: 'auth',
  },
];
