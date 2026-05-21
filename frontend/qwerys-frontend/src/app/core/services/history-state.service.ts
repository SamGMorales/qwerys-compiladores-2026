import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export interface PendingReanalyze {
  query: string;
  databaseType?: string;
}

@Injectable({ providedIn: 'root' })
export class HistoryStateService {
  private readonly pendingReanalyze = new BehaviorSubject<PendingReanalyze | null>(null);
  readonly pendingReanalyze$ = this.pendingReanalyze.asObservable();

  setPendingReanalyze(payload: PendingReanalyze): void {
    this.pendingReanalyze.next(payload);
  }

  clearPendingReanalyze(): void {
    this.pendingReanalyze.next(null);
  }
}
