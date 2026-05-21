import {
  Directive,
  ElementRef,
  OnDestroy,
  OnInit,
  Renderer2,
  inject,
} from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { AccessibilityService } from '../../core/services/accessibility.service';
import { Subscription } from 'rxjs';
import { distinctUntilChanged, map } from 'rxjs/operators';

let collapsibleIdSeq = 0;

/**
 * En modo dislexía muestra un botón antes del bloque para colapsar/expandir contenido denso.
 * Corregido vs. snippet genérico: no repite insert DOM en cada emisión de `profile$`,
 * usa `distinctUntilChanged`, ARIA (`aria-expanded`, `aria-controls`) e i18n.
 */
@Directive({ selector: '[qwerysCollapsible]', standalone: true })
export class CollapsibleSectionDirective implements OnInit, OnDestroy {
  private readonly el = inject(ElementRef<HTMLElement>);
  private readonly renderer = inject(Renderer2);
  private readonly accessibility = inject(AccessibilityService);
  private readonly translate = inject(TranslateService);

  private btn: HTMLButtonElement | null = null;
  private collapsed = false;
  private dyslexiaSub = Subscription.EMPTY;
  private langSub = Subscription.EMPTY;
  private clickRemove: (() => void) | null = null;

  private controlId = '';

  ngOnInit(): void {
    const host = this.el.nativeElement;
    const existing = host.id?.trim();
    this.controlId = existing || `qwerys-collapsible-${++collapsibleIdSeq}`;
    if (!existing) {
      this.renderer.setAttribute(host, 'id', this.controlId);
    }

    this.btn = this.renderer.createElement('button') as HTMLButtonElement;
    this.renderer.setAttribute(this.btn, 'type', 'button');
    this.renderer.addClass(this.btn, 'collapse-toggle-btn');
    this.renderer.setAttribute(this.btn, 'aria-controls', this.controlId);

    this.clickRemove = this.renderer.listen(this.btn, 'click', () => this.toggle());

    this.dyslexiaSub = this.accessibility.profile$
      .pipe(
        map((p) => p.dyslexiaMode),
        distinctUntilChanged()
      )
      .subscribe((on) => {
        if (on) {
          this.attachToggleUi();
        } else {
          this.detachToggleUi();
        }
      });

    this.langSub = this.translate.onLangChange.subscribe(() => this.refreshLabels());
  }

  private attachToggleUi(): void {
    if (!this.btn) return;
    const host = this.el.nativeElement;
    const parent = host.parentNode;
    if (!parent) return;

    this.collapsed = false;
    this.renderer.removeStyle(host, 'display');

    if (this.btn.parentNode !== parent) {
      this.renderer.insertBefore(parent, this.btn, host);
    }
    this.refreshLabels();
    this.setAriaExpanded();
  }

  private detachToggleUi(): void {
    this.expandContent();
    if (this.btn?.parentNode) {
      this.renderer.removeChild(this.btn.parentNode, this.btn);
    }
    this.refreshLabels();
    this.setAriaExpanded();
  }

  private toggle(): void {
    if (this.collapsed) {
      this.expandContent();
    } else {
      this.collapseContent();
    }
  }

  private collapseContent(): void {
    this.collapsed = true;
    this.renderer.setStyle(this.el.nativeElement, 'display', 'none');
    this.refreshLabels();
    this.setAriaExpanded();
  }

  private expandContent(): void {
    this.collapsed = false;
    this.renderer.removeStyle(this.el.nativeElement, 'display');
    this.refreshLabels();
    this.setAriaExpanded();
  }

  private refreshLabels(): void {
    if (!this.btn) return;
    const labelKey = this.collapsed
      ? 'analyzer.collapsibleExpand'
      : 'analyzer.collapsibleCollapse';
    const ariaKey = this.collapsed
      ? 'analyzer.collapsibleAriaExpand'
      : 'analyzer.collapsibleAriaCollapse';
    this.renderer.setProperty(this.btn, 'textContent', this.translate.instant(labelKey));
    this.renderer.setAttribute(this.btn, 'aria-label', this.translate.instant(ariaKey));
  }

  private setAriaExpanded(): void {
    if (!this.btn) return;
    const expanded = !this.collapsed;
    this.renderer.setAttribute(this.btn, 'aria-expanded', expanded ? 'true' : 'false');
  }

  ngOnDestroy(): void {
    this.dyslexiaSub.unsubscribe();
    this.langSub.unsubscribe();
    this.clickRemove?.();
    this.clickRemove = null;
    if (this.btn?.parentNode) {
      this.renderer.removeChild(this.btn.parentNode, this.btn);
    }
    this.renderer.removeStyle(this.el.nativeElement, 'display');
    this.btn = null;
  }
}
