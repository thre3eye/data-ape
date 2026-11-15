import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DaBusService } from './da-bus.service';
import { DaMongoService, DatabaseDescription, isDocumentId } from './da-mongo.service';
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

  it('sends the session write token with mutations only after a writable connection', () => {
    service.connect('mongodb://mongo:27017/example').subscribe();
    http.expectOne('connect').flush({ dbName: 'example', tables: [], decimaldigits: 2, writeToken: 'test-token' });
    expect(service.canWrite).toBeTrue();
    service.saveRecord('example', 'sample', '{"_id":"one"}', '{"_id":"one","value":2}').subscribe();
    const request = http.expectOne('save/example/sample');
    expect(request.request.headers.get('X-Data-Ape-CSRF')).toBe('test-token');
    expect(request.request.body.original).toBe('{"_id":"one"}');
    request.flush({ result: true });
  });

  it('distinguishes ObjectIds from string IDs in document and delete requests', () => {
    const hex = '5ccd00001234567890abcdef';
    service.connect('mongodb://mongo:27017/example').subscribe();
    http.expectOne('connect').flush({ dbName: 'example', tables: [], decimaldigits: 2, writeToken: 'test-token' });
    service.getDocument('example', 'sample', { $oid: hex }).subscribe();
    http.expectOne(`document/example/sample/${hex}?idType=objectId`).flush({ document: '{}' });
    service.deleteRecord('example', 'sample', { $oid: hex }).subscribe();
    const deletion = http.expectOne(`delete/example/sample/${hex}?idType=objectId`);
    expect(deletion.request.headers.get('X-Data-Ape-CSRF')).toBe('test-token');
    deletion.flush({ result: true });
    service.getDocument('example', 'sample', hex).subscribe();
    http.expectOne(`document/example/sample/${hex}`).flush({ document: '{}' });
    expect(isDocumentId({ $oid: hex })).toBeTrue();
    expect(isDocumentId({ $oid: 'invalid' })).toBeFalse();
    expect(isDocumentId({ timestamp: 1557094407, date: 1557094407000 })).toBeFalse();
  });

  it('propagates archive failures instead of treating them as missing records', () => {
    service.connect('mongodb://mongo:27017/example').subscribe();
    http.expectOne('connect').flush({ dbName: 'example', tables: [], decimaldigits: 2, writeToken: 'test-token' });
    const error = jasmine.createSpy('error');
    service.deleteRecord('example', 'sample', 'one').subscribe({ error });
    http.expectOne('delete/example/sample/one').flush(
      { msg: 'Archive retained; verify both collections.', id: 0 }, { status: 503, statusText: 'Unavailable' });
    expect(error).toHaveBeenCalled();
    expect(messages.publish).toHaveBeenCalledWith(
      jasmine.objectContaining({ text: 'Archive retained; verify both collections.' }));
  });
});
