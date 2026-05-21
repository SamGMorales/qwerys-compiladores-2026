import {
  ChangeDetectorRef,
  Component,
  DestroyRef,
  Input,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { AnalysisError, QueryIssue } from '../../../core/services/query.service';

@Component({
  selector: 'app-student-progress-bar',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './student-progress-bar.component.html',
  styleUrls: ['./student-progress-bar.component.scss'],
})
export class StudentProgressBarComponent {
  private readonly translate = inject(TranslateService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);

  @Input() errors: AnalysisError[] | undefined;
  @Input() issues: QueryIssue[] | undefined;
  /** Extra AI complement warnings (additionalWarnings). */
  @Input() aiWarnings = 0;
  /** Extra AI complement errors (additionalErrors). */
  @Input() aiErrors = 0;
  @Input() labelKey = 'student.practicesScore';
  @Input() labelParams: Record<string, string | number> | undefined;
  /** Si se define, sustituye la etiqueta traducida de {@link labelKey}. */
  @Input() labelOverride?: string;

  constructor() {
    this.translate.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.cdr.markForCheck());
  }

  get displayLabel(): string {
    if (this.labelOverride?.trim()) {
      return this.labelOverride;
    }
    return this.translate.instant(this.labelKey, this.labelParams);
  }

  get progress(): number {
    const errN = (this.errors?.length ?? 0) + Math.max(0, this.aiErrors);
    const warnN =
      (this.issues?.filter((i) => i.type === 'warning' || i.type === 'error').length ?? 0)
      + Math.max(0, this.aiWarnings);
    if (errN === 0 && warnN === 0) return 100;
    return Math.max(0, 100 - errN * 20 - warnN * 5);
  }
}
