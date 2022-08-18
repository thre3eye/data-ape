import { TestBed } from '@angular/core/testing';

import { DaBusService } from './da-bus.service';

describe('DaBusService', () => {
  let service: DaBusService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(DaBusService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
