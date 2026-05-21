import { Component, ElementRef, Input, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatTreeModule, MatTreeNestedDataSource } from '@angular/material/tree';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { TranslateModule } from '@ngx-translate/core';

import { SyntaxNode } from '../../../core/models/query-analysis.model';

export interface AstTreeNode {
  type: string;
  value?: string;
  childCount: number;
  children: AstTreeNode[];
}

@Component({
  selector: 'app-ast-tree',
  standalone: true,
  imports: [CommonModule, MatTreeModule, MatIconModule, MatButtonModule, TranslateModule],
  templateUrl: './ast-tree.component.html',
  styleUrls: ['./ast-tree.component.scss'],
})
export class AstTreeComponent {
  @ViewChild('treeRoot', { read: ElementRef }) private treeRoot?: ElementRef<HTMLElement>;

  readonly treeControl = new NestedTreeControl<AstTreeNode>((node) => node.children);
  readonly dataSource = new MatTreeNestedDataSource<AstTreeNode>();

  @Input() set ast(root: SyntaxNode | null | undefined) {
    if (!root) {
      this.dataSource.data = [];
      return;
    }
    this.dataSource.data = [this.toTreeNode(root)];
    this.treeControl.expand(this.dataSource.data[0]);
  }

  hasChild = (_index: number, node: AstTreeNode): boolean =>
    node.children.length > 0;

  onTreeKeydown(event: KeyboardEvent): void {
    const toggles = this.getToggleButtons();
    if (!toggles.length) {
      return;
    }

    const active = document.activeElement as HTMLElement | null;
    let index = toggles.findIndex((btn) => btn === active);
    if (index < 0) {
      index = 0;
    }

    const node = this.findNodeByToggle(toggles[index]);
    if (!node) {
      return;
    }

    switch (event.key) {
      case 'ArrowRight':
        if (this.hasChild(0, node) && !this.treeControl.isExpanded(node)) {
          event.preventDefault();
          this.treeControl.expand(node);
        }
        break;
      case 'ArrowLeft':
        if (this.hasChild(0, node) && this.treeControl.isExpanded(node)) {
          event.preventDefault();
          this.treeControl.collapse(node);
        }
        break;
      case 'ArrowDown':
        event.preventDefault();
        toggles[Math.min(index + 1, toggles.length - 1)]?.focus();
        break;
      case 'ArrowUp':
        event.preventDefault();
        toggles[Math.max(index - 1, 0)]?.focus();
        break;
      case 'Home':
        event.preventDefault();
        toggles[0]?.focus();
        break;
      case 'End':
        event.preventDefault();
        toggles[toggles.length - 1]?.focus();
        break;
      default:
        break;
    }
  }

  private getToggleButtons(): HTMLButtonElement[] {
    const root = this.treeRoot?.nativeElement;
    if (!root) {
      return [];
    }
    return Array.from(root.querySelectorAll<HTMLButtonElement>('.ast-tree-toggle:not(:disabled)'));
  }

  private findNodeByToggle(button: HTMLButtonElement): AstTreeNode | null {
    const label = button.parentElement?.querySelector('.ast-node')?.textContent?.trim();
    if (!label) {
      return null;
    }
    return this.findNodeByLabel(this.dataSource.data, label);
  }

  private findNodeByLabel(nodes: AstTreeNode[], label: string): AstTreeNode | null {
    for (const node of nodes) {
      if (this.nodeLabel(node) === label) {
        return node;
      }
      const child = this.findNodeByLabel(node.children, label);
      if (child) {
        return child;
      }
    }
    return null;
  }

  nodeCssClass(type: string): string {
    const t = type.toUpperCase();
    if (
      /KEYWORD|KW_|TOKEN_KW|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|JOIN|CREATE|DROP|ALTER/.test(
        t
      )
    ) {
      return 'ast-node--keyword';
    }
    if (
      /IDENT|NAME|TABLE|COLUMN|FIELD|ALIAS|SCHEMA|COLLECTION|INDEX/.test(t)
    ) {
      return 'ast-node--identifier';
    }
    if (/OPERATOR|OP_|COMPARISON|LOGICAL|ARITH|EQ|NEQ|GT|LT|PLUS|MINUS/.test(t)) {
      return 'ast-node--operator';
    }
    if (/LITERAL|STRING|NUMBER|INT|FLOAT|BOOL|NULL|CONST|VALUE|QUOTE/.test(t)) {
      return 'ast-node--literal';
    }
    return 'ast-node--default';
  }

  nodeLabel(node: AstTreeNode): string {
    const valuePart = node.value?.trim()
      ? ` · "${node.value.length > 40 ? node.value.slice(0, 40) + '…' : node.value}"`
      : '';
    return `${node.type}${valuePart} (${node.childCount})`;
  }

  private toTreeNode(node: SyntaxNode): AstTreeNode {
    const children = (node.children ?? []).map((c) => this.toTreeNode(c));
    return {
      type: node.type || 'NODE',
      value: node.value,
      childCount: children.length,
      children,
    };
  }
}
