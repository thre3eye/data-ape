import { DaThemeService } from './da-theme.service';

describe('DaThemeService', () => {
  let originalTheme: string | undefined;

  beforeEach(() => {
    originalTheme = document.documentElement.dataset['theme'];
  });

  afterEach(() => {
    if (originalTheme === undefined) {
      delete document.documentElement.dataset['theme'];
    } else {
      document.documentElement.dataset['theme'] = originalTheme;
    }
  });

  it('defaults to dark and restores a saved light preference', () => {
    const get = spyOn(Storage.prototype, 'getItem').and.returnValue(null);
    expect(new DaThemeService(document).current).toBe('dark');
    expect(document.documentElement.dataset['theme']).toBe('dark');
    get.and.returnValue('light');
    expect(new DaThemeService(document).current).toBe('light');
    expect(document.documentElement.dataset['theme']).toBe('light');
  });

  it('applies and persists changes and reports storage errors', () => {
    spyOn(Storage.prototype, 'getItem').and.returnValue(null);
    const save = spyOn(Storage.prototype, 'setItem');
    const alert = spyOn(window, 'alert');
    spyOn(console, 'warn');
    const service = new DaThemeService(document);
    service.set('light');
    expect(save).toHaveBeenCalledWith('data-ape-theme', 'light');
    expect(document.documentElement.dataset['theme']).toBe('light');
    save.and.throwError('Storage unavailable');
    service.set('dark');
    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(alert).toHaveBeenCalled();
  });
});
