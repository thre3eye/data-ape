import { TestBed } from '@angular/core/testing';

import { DaMsgService } from './da-msg.service';

describe('DaMsgService', () => {
  let service: DaMsgService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(DaMsgService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
