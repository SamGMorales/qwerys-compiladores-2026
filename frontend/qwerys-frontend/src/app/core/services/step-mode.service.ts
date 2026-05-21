import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export type AnalysisStep = 'write' | 'configure' | 'analyze' | 'results';

const STEP_ORDER: AnalysisStep[] = ['write', 'configure', 'analyze', 'results'];

@Injectable({ providedIn: 'root' })
export class StepModeService {
  private readonly stepSubject = new BehaviorSubject<AnalysisStep>('write');

  readonly currentStep$ = this.stepSubject.asObservable();

  goTo(step: AnalysisStep): void {
    this.stepSubject.next(step);
  }

  get current(): AnalysisStep {
    return this.stepSubject.value;
  }

  next(): void {
    const idx = STEP_ORDER.indexOf(this.current);
    if (idx >= 0 && idx < STEP_ORDER.length - 1) {
      this.stepSubject.next(STEP_ORDER[idx + 1]);
    }
  }

  /** Retrocede un paso (adicional al snippet original — mejora uso real modo TDAH). */
  previous(): void {
    const idx = STEP_ORDER.indexOf(this.current);
    if (idx > 0) {
      this.stepSubject.next(STEP_ORDER[idx - 1]);
    }
  }

  reset(): void {
    this.stepSubject.next('write');
  }
}
