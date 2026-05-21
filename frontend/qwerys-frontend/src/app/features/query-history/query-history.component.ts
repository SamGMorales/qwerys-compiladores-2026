import {
  Component,
  DestroyRef,
  OnInit,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, debounceTime, distinctUntilChanged, finalize } from 'rxjs';

import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import {
  HistoryEntry,
  QueryHistoryService,
} from '../../core/services/query-history.service';
import { HistoryStateService } from '../../core/services/history-state.service';
import { AuthService } from '../../core/services/auth.service';
import { QueryAnalysisResult } from '../../core/services/query.service';
import { CollapsibleSectionDirective } from '../../shared/directives/collapsible-section.directive';

type HistoryFilter = 'all' | 'valid' | 'invalid' | 'favorites';

@Component({
  selector: 'app-query-history',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    CollapsibleSectionDirective,
  ],
  templateUrl: './query-history.component.html',
  styleUrls: ['./query-history.component.scss'],
})
export class QueryHistoryComponent implements OnInit {
  private readonly historyService = inject(QueryHistoryService);
  private readonly historyState = inject(HistoryStateService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly searchInput$ = new Subject<string>();

  isMobile = false;

  entries: HistoryEntry[] = [];
  filteredEntries: HistoryEntry[] = [];
  selectedEntry: HistoryEntry | null = null;
  selectedResult: QueryAnalysisResult | null = null;
  selectedAiComplement: import('../../core/models/complement-analysis.model').AiComplementState | null =
    null;

  filter: HistoryFilter = 'all';
  searchKeyword = '';
  currentPage = 0;
  pageSize = 20;
  totalPages = 0;
  totalElements = 0;
  isLoading = false;
  errorKey: string | null = null;

  confirmDeleteId: number | null = null;
  confirmClearAll = false;

  ngOnInit(): void {
    this.breakpointObserver
      .observe(['(max-width: 767.98px)'])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        this.isMobile = result.matches;
      });

    if (!this.authService.isLoggedIn()) {
      this.errorKey = 'history.errors.authRequired';
      return;
    }

    this.loadEntries();

    this.searchInput$
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe((keyword) => {
        this.searchKeyword = keyword;
        if (keyword.trim()) {
          this.loadSearch(keyword.trim());
        } else {
          this.loadEntries();
        }
      });
  }

  onSearchInput(value: string): void {
    this.searchInput$.next(value);
  }

  setFilter(next: HistoryFilter): void {
    this.filter = next;
    this.currentPage = 0;
    this.selectedEntry = null;
    this.selectedResult = null;
    this.selectedAiComplement = null;
    if (this.searchKeyword.trim()) {
      this.loadSearch(this.searchKeyword.trim());
      return;
    }
    this.loadEntries();
  }

  loadEntries(): void {
    if (this.filter === 'favorites') {
      this.loadList(() => this.historyService.getFavorites());
      return;
    }
    if (this.filter === 'valid') {
      this.loadList(() => this.historyService.getValidOnly());
      return;
    }
    if (this.filter === 'invalid') {
      this.loadList(() => this.historyService.getInvalidOnly());
      return;
    }
    this.isLoading = true;
    this.errorKey = null;
    this.historyService
      .getHistory(this.currentPage, this.pageSize)
      .pipe(
        finalize(() => (this.isLoading = false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (page) => {
          this.entries = page.content ?? [];
          this.filteredEntries = this.entries;
          this.totalPages = page.totalPages ?? 0;
          this.totalElements = page.totalElements ?? 0;
          this.currentPage = page.number ?? 0;
        },
        error: (err: Error) => {
          this.errorKey = err.message;
          this.entries = [];
          this.filteredEntries = [];
        },
      });
  }

  private loadList(fetch: () => import('rxjs').Observable<HistoryEntry[]>): void {
    this.isLoading = true;
    this.errorKey = null;
    fetch()
      .pipe(
        finalize(() => (this.isLoading = false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          this.entries = list ?? [];
          this.filteredEntries = this.entries;
          this.totalPages = 1;
          this.totalElements = this.entries.length;
          this.currentPage = 0;
        },
        error: (err: Error) => {
          this.errorKey = err.message;
          this.entries = [];
          this.filteredEntries = [];
        },
      });
  }

  private loadSearch(keyword: string): void {
    this.isLoading = true;
    this.errorKey = null;
    this.historyService
      .search(keyword)
      .pipe(
        finalize(() => (this.isLoading = false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (list) => {
          let rows = list ?? [];
          if (this.filter === 'valid') {
            rows = rows.filter((e) => e.valid);
          } else if (this.filter === 'invalid') {
            rows = rows.filter((e) => !e.valid);
          } else if (this.filter === 'favorites') {
            rows = rows.filter((e) => e.favorite);
          }
          this.entries = rows;
          this.filteredEntries = rows;
          this.totalPages = 1;
          this.totalElements = rows.length;
        },
        error: (err: Error) => {
          this.errorKey = err.message;
          this.entries = [];
          this.filteredEntries = [];
        },
      });
  }

  selectEntry(entry: HistoryEntry): void {
    this.selectedEntry = entry;
    this.selectedResult = this.historyService.parseResult(entry);
    this.selectedAiComplement = this.historyService.parseAiComplement(entry);
  }

  backToHistoryList(): void {
    this.selectedEntry = null;
    this.selectedResult = null;
    this.selectedAiComplement = null;
  }

  toggleFavorite(entry: HistoryEntry, event: Event): void {
    event.stopPropagation();
    this.historyService.toggleFavorite(entry.id).subscribe({
      next: (updated) => {
        entry.favorite = updated.favorite;
        if (this.selectedEntry?.id === entry.id) {
          this.selectedEntry = { ...entry };
        }
        if (this.filter === 'favorites' && !updated.favorite) {
          this.filteredEntries = this.filteredEntries.filter((e) => e.id !== entry.id);
          this.entries = this.filteredEntries;
        }
      },
      error: (err: Error) => {
        this.errorKey = err.message;
      },
    });
  }

  reanalyze(entry: HistoryEntry, event: Event): void {
    event.stopPropagation();
    this.historyState.setPendingReanalyze({
      query: entry.query,
      databaseType: entry.databaseType,
    });
    void this.router.navigate(['/analyzer']);
  }

  requestDelete(entry: HistoryEntry, event: Event): void {
    event.stopPropagation();
    this.confirmDeleteId = entry.id;
    this.confirmClearAll = false;
  }

  cancelDelete(): void {
    this.confirmDeleteId = null;
    this.confirmClearAll = false;
  }

  confirmDeleteEntry(): void {
    const id = this.confirmDeleteId;
    if (id == null) {
      return;
    }
    this.historyService.deleteEntry(id).subscribe({
      next: () => {
        this.filteredEntries = this.filteredEntries.filter((e) => e.id !== id);
        this.entries = this.filteredEntries;
        if (this.selectedEntry?.id === id) {
          this.selectedEntry = null;
          this.selectedResult = null;
          this.selectedAiComplement = null;
        }
        this.confirmDeleteId = null;
      },
      error: (err: Error) => {
        this.errorKey = err.message;
        this.confirmDeleteId = null;
      },
    });
  }

  requestClearAll(): void {
    this.confirmClearAll = true;
    this.confirmDeleteId = null;
  }

  confirmClearHistory(): void {
    this.historyService.deleteAll().subscribe({
      next: () => {
        this.entries = [];
        this.filteredEntries = [];
        this.selectedEntry = null;
        this.selectedResult = null;
        this.selectedAiComplement = null;
        this.confirmClearAll = false;
        this.totalElements = 0;
        this.totalPages = 0;
      },
      error: (err: Error) => {
        this.errorKey = err.message;
        this.confirmClearAll = false;
      },
    });
  }

  prevPage(): void {
    if (this.currentPage > 0 && this.filter === 'all' && !this.searchKeyword.trim()) {
      this.currentPage--;
      this.loadEntries();
    }
  }

  nextPage(): void {
    if (
      this.filter === 'all' &&
      !this.searchKeyword.trim() &&
      this.currentPage < this.totalPages - 1
    ) {
      this.currentPage++;
      this.loadEntries();
    }
  }

  truncateQuery(text: string, max = 72): string {
    const q = text?.replace(/\s+/g, ' ').trim() ?? '';
    return q.length <= max ? q : `${q.slice(0, max)}…`;
  }

  formatDate(value: string | number[] | null | undefined): string {
    const date = this.parseAnalyzedAt(value);
    if (!date) {
      return '';
    }
    return date.toLocaleString();
  }

  /** Supports ISO strings and Jackson LocalDateTime arrays [year, month, day, hour, min, sec, nano?]. */
  private parseAnalyzedAt(value: string | number[] | null | undefined): Date | null {
    if (value == null) {
      return null;
    }
    if (Array.isArray(value)) {
      if (value.length < 3) {
        return null;
      }
      const [year, month, day, hour = 0, minute = 0, second = 0] = value;
      const d = new Date(year, month - 1, day, hour, minute, second);
      return Number.isNaN(d.getTime()) ? null : d;
    }
    const raw = String(value).trim();
    if (!raw) {
      return null;
    }
    const d = new Date(raw);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  hasAiReview(entry: HistoryEntry): boolean {
    return entry.aiAssistedValid != null;
  }

  nativeValidLabel(entry: HistoryEntry): string {
    return entry.valid ? 'history.badgeNativeValid' : 'history.badgeNativeInvalid';
  }

  aiValidLabel(entry: HistoryEntry): string {
    return entry.aiAssistedValid ? 'history.badgeAiValid' : 'history.badgeAiInvalid';
  }

  dbLabel(db: string): string {
    if (!db) {
      return '—';
    }
    if (db.startsWith('custom::')) {
      const parts = db.split('::');
      return parts.length > 1 ? parts[1] : db;
    }
    return db.toUpperCase();
  }
}
