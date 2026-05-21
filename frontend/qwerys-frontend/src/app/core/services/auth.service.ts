import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { tap } from 'rxjs/operators';
import { ThemeService } from './theme.service';

const TOKEN_KEY = 'qwerys-auth-token';
const GUEST_KEY = 'qwerys-guest-mode';
const API_BASE = '/api/auth';

/** Response shape from GET /api/auth/me */
export interface AuthUserProfile {
  id: number;
  name: string;
  email: string;
  language?: string;
  darkTheme?: boolean;
  blindMode?: boolean;
  lowVisionMode?: boolean;
  dyslexiaMode?: boolean;
  deafMode?: boolean;
  adhdMode?: boolean;
  studentMode?: boolean;
  expertMode?: boolean;
  customDatabases?: string[];
  createdAt?: string;
}

/** Partial update for PUT /api/auth/profile */
export interface ServerProfilePatch {
  language?: string;
  darkTheme?: boolean;
  blindMode?: boolean;
  lowVisionMode?: boolean;
  dyslexiaMode?: boolean;
  deafMode?: boolean;
  adhdMode?: boolean;
  studentMode?: boolean;
  expertMode?: boolean;
  customDatabases?: string[];
}

interface AuthResponse {
  token: string;
  name: string;
  email: string;
  darkTheme: boolean;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly themeService = inject(ThemeService);

  readonly isAuthenticated$ = new BehaviorSubject<boolean>(this.isLoggedIn());
  readonly currentUser$ = new BehaviorSubject<{ name: string; email: string; darkTheme: boolean } | null>(null);

  login(email: string, password: string) {
    return this.http.post<AuthResponse>(`${API_BASE}/login`, { email, password }).pipe(
      tap(res => this.handleAuthSuccess(res))
    );
  }

  register(name: string, email: string, password: string) {
    return this.http.post<AuthResponse>(`${API_BASE}/register`, { name, email, password }).pipe(
      tap(res => this.handleAuthSuccess(res))
    );
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(GUEST_KEY);
    this.isAuthenticated$.next(false);
    this.currentUser$.next(null);
    this.router.navigate(['/auth']);
  }

  isLoggedIn(): boolean {
    return !!localStorage.getItem(TOKEN_KEY);
  }

  /** JWT for API headers (Bearer), or null if not signed in. */
  getAccessToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  enterAsGuest(): void {
    sessionStorage.setItem(GUEST_KEY, 'true');
    this.router.navigate(['/onboarding/guest']);
  }

  isGuest(): boolean {
    return sessionStorage.getItem(GUEST_KEY) === 'true';
  }

  clearSession(): void {
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(GUEST_KEY);
    this.isAuthenticated$.next(false);
    this.currentUser$.next(null);
  }

  fetchProfile(): Observable<AuthUserProfile> {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      return throwError(() => new Error('Not authenticated'));
    }
    return this.http.get<AuthUserProfile>(`${API_BASE}/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  }

  /**
   * Sync accessibility / custom engines with the server (registered users only).
   */
  updateProfile(profile: ServerProfilePatch): Observable<Record<string, unknown>> {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      return throwError(() => new Error('Not authenticated'));
    }
    return this.http.put<Record<string, unknown>>(`${API_BASE}/profile`, profile, {
      headers: { Authorization: `Bearer ${token}` },
    });
  }

  private handleAuthSuccess(res: AuthResponse): void {
    localStorage.setItem(TOKEN_KEY, res.token);
    sessionStorage.removeItem(GUEST_KEY);
    this.isAuthenticated$.next(true);
    this.currentUser$.next({ name: res.name, email: res.email, darkTheme: res.darkTheme });
    this.themeService.setTheme(res.darkTheme ?? false);
  }
}
