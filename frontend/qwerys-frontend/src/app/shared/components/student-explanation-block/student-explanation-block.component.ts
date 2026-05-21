import {
  ChangeDetectorRef,
  Component,
  DestroyRef,
  Input,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  StudentExplanationsService,
  StudentExplanation,
} from '../../services/student-explanations.service';

@Component({
  selector: 'app-student-explanation-block',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './student-explanation-block.component.html',
  styleUrls: ['./student-explanation-block.component.scss'],
})
export class StudentExplanationBlockComponent {
  private readonly studentExplanations = inject(StudentExplanationsService);
  private readonly translate = inject(TranslateService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);

  private errorCode: string | undefined;
  private apiEducation: StudentExplanation | null | undefined;

  @Input() set code(value: string | undefined) {
    this.errorCode = value?.trim() || undefined;
    this.refreshExplanation();
  }

  @Input() set education(value: StudentExplanation | null | undefined) {
    this.apiEducation = value;
    this.refreshExplanation();
  }

  explanation: StudentExplanation | null = null;

  constructor() {
    this.translate.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.refreshExplanation();
        this.cdr.markForCheck();
      });
  }

  private refreshExplanation(): void {
    this.explanation = this.studentExplanations.getExplanation(
      this.errorCode,
      this.apiEducation
    );
  }
}
