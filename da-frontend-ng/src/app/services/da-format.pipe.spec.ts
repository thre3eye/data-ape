import { DaFormatPipe } from './da-format.pipe';
import { TableDescriptionImpl } from './da-mongo.service';

describe('DaFormatPipe', () => {
  it('create an instance', () => {
    const pipe = new DaFormatPipe();
    expect(pipe).toBeTruthy();
  });

  it('distinguishes ObjectIds from identical string IDs in the table', () => {
    const table = new TableDescriptionImpl();
    table.headers = ['_id'];
    table.fieldMap = { _id: 'objectId' };
    const pipe = new DaFormatPipe();
    expect(pipe.transform({ $oid: '5ccd00001234567890abcdef' }, table, 0))
      .toBe('ObjectId(5ccd00001234567890abcdef)');
    expect(pipe.transform('5ccd00001234567890abcdef', table, 0))
      .toBe('5ccd00001234567890abcdef');
  });
});
