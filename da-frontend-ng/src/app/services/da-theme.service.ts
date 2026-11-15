import { DOCUMENT } from '@angular/common';
import { Inject, Injectable } from '@angular/core';

export type DaTheme = 'dark' | 'light';

@Injectable({ providedIn: 'root' })
export class DaThemeService {
  public current: DaTheme = 'dark';

  constructor(@Inject(DOCUMENT) private document: Document) {
    try {
      if (this.document.defaultView?.localStorage.getItem('data-ape-theme') === 'light') {
        this.current = 'light';
      }
    } catch (error) {
      console.warn('Unable to read the saved theme preference.', error);
    }
    this.apply();
  }

  public set(theme: DaTheme): void {
    if (theme !== 'dark' && theme !== 'light') {
      throw new Error('Invalid theme');
    }
    this.current = theme;
    this.apply();
    try {
      const storage = this.document.defaultView?.localStorage;
      if (!storage) {
        throw new Error('Browser storage is unavailable');
      }
      storage.setItem('data-ape-theme', theme);
    } catch (error) {
      console.warn('Unable to save the theme preference.', error);
      this.document.defaultView?.alert('Theme changed, but could not be saved for your next visit.');
    }
  }

  private apply(): void {
    this.document.documentElement.dataset['theme'] = this.current;
  }
}
