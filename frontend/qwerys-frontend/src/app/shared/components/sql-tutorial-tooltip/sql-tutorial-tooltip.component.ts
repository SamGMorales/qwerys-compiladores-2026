import {
  ChangeDetectorRef,
  Component,
  DestroyRef,
  Input,
  ViewEncapsulation,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { keywordToI18nKey } from '../../services/student-keyword-patterns';

/**
 * Tooltip educativo al pasar el ratón (modo estudiante).
 * En un textarea no hay hover por token; se enlaza al panel de keywords detectadas.
 */
@Component({
  selector: 'app-sql-tutorial-tooltip',
  standalone: true,
  imports: [MatTooltipModule, TranslateModule],
  templateUrl: './sql-tutorial-tooltip.component.html',
  styleUrls: ['./sql-tutorial-tooltip.component.scss'],
  encapsulation: ViewEncapsulation.None,
})
export class SqlTutorialTooltipComponent {
  private readonly translate = inject(TranslateService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);

  private keywordValue = '';

  @Input() set keyword(value: string) {
    this.keywordValue = value?.trim() ?? '';
    this.refreshTooltip();
  }

  tooltipText = '';

  constructor() {
    this.translate.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.refreshTooltip();
        this.cdr.markForCheck();
      });
  }

  private refreshTooltip(): void {
    const key = keywordToI18nKey(this.keywordValue);
    const base = `student.tooltips.${key}`;
    const purpose = this.resolve(`${base}.purpose`);
    const syntax = this.resolve(`${base}.syntax`);
    const example = this.resolve(`${base}.example`);

    const parts: string[] = [];
    if (purpose) {
      parts.push(purpose);
    }
    if (syntax) {
      parts.push(`${this.translate.instant('student.tooltipSyntax')}: ${syntax}`);
    }
    if (example) {
      parts.push(`${this.translate.instant('student.tooltipExample')}: ${example}`);
    }

    if (parts.length === 0) {
      const short = this.resolve(`student.keywords.${key}`);
      this.tooltipText = short || this.keywordValue;
      return;
    }

    this.tooltipText = parts.join('\n\n');
  }

  private resolve(i18nKey: string): string {
    const t = this.translate.instant(i18nKey);
    return t !== i18nKey ? t : '';
  }
}
