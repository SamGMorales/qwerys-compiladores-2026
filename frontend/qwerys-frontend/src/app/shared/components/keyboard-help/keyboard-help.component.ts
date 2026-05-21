import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';

interface ShortcutRow {
  keysKey: string;
  descKey: string;
}

@Component({
  selector: 'app-keyboard-help',
  standalone: true,
  imports: [CommonModule, TranslateModule, MatDialogModule, MatButtonModule],
  templateUrl: './keyboard-help.component.html',
  styleUrl: './keyboard-help.component.scss',
})
export class KeyboardHelpComponent {
  readonly rows: ShortcutRow[] = [
    { keysKey: 'shortcuts.keys.analyze', descKey: 'shortcuts.analyze' },
    { keysKey: 'shortcuts.keys.clear', descKey: 'shortcuts.clear' },
    { keysKey: 'shortcuts.keys.toggleVoice', descKey: 'shortcuts.toggleVoice' },
    { keysKey: 'shortcuts.keys.help', descKey: 'shortcuts.help' },
    { keysKey: 'shortcuts.keys.close', descKey: 'shortcuts.close' },
    { keysKey: 'shortcuts.keys.navAnalyzer', descKey: 'shortcuts.navAnalyzer' },
    { keysKey: 'shortcuts.keys.navMigration', descKey: 'shortcuts.navMigration' },
    { keysKey: 'shortcuts.keys.navSchema', descKey: 'shortcuts.navSchema' },
  ];
}
