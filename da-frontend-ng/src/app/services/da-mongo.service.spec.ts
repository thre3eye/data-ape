import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DaBusService } from './da-bus.service';
import { DaMongoService, DatabaseDescription } from './da-mongo.service';
import { DaMsgService } from './da-msg.service';

describe('DaMongoService connection', () => {
  let service: DaMongoService;
  let http: HttpTestingController;
  let messages: jasmine.SpyObj<DaMsgService>;

  beforeEach(() => {
    messages = jasmine.createSpyObj('DaMsgService', ['publish']);
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [{ provide: DaMsgService, useValue: messages }]
    });
    service = TestBed.inject(DaMongoService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends the complete URI only in the POST body', () => {
    const uri = 'mongodb://user:password@mongo:27017/example';
    const expected: DatabaseDescription = { dbName: 'example', tables: [], decimaldigits: 2 };
    const result = jasmine.createSpy('result');
    service.connect(uri).subscribe(result);

    const request = http.expectOne('connect');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ cstr: uri });
    expect(request.request.params.keys()).toEqual([]);
    request.flush(expected);
    expect(result).toHaveBeenCalledOnceWith(expected);
    expect(service.isLoading.value).toBeFalse();
  });

  it('reports connection failures without presenting them as success', () => {
    const result = jasmine.createSpy('result');
    service.connect('mongodb://mongo:27017/example').subscribe(result);
    http.expectOne('connect').flush({ msg: 'Database request failed' }, { status: 503, statusText: 'Unavailable' });

    expect(result).toHaveBeenCalledOnceWith(null);
    expect(TestBed.inject(DaBusService).isTopVisible.value).toBeTrue();
    expect(messages.publish).toHaveBeenCalledWith(jasmine.objectContaining({ text: 'Error Connecting' }));
    expect(service.isLoading.value).toBeFalse();
  });
});
