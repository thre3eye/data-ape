import { TestBed } from '@angular/core/testing';

import { DaLogService } from './da-log.service';

describe('DaLogService', () => {
  let service: DaLogService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(DaLogService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
