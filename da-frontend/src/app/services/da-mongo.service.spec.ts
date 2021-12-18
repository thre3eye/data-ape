import { TestBed } from '@angular/core/testing';

import { DaMongoService } from './da-mongo.service';

describe('DaMongoService', () => {
  let service: DaMongoService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(DaMongoService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
