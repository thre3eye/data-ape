import { TestBed } from '@angular/core/testing';

import { DaConfigService } from './da-config.service';

describe('DaConfigService', () => {
  let service: DaConfigService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(DaConfigService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
