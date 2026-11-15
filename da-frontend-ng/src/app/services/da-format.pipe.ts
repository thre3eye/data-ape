import { Injectable, Pipe, PipeTransform } from '@angular/core';
import { isDocumentId, TableDescription } from './da-mongo.service';

@Pipe({
  name: 'daFormat'
})
@Injectable({
  providedIn: 'root',
})
export class DaFormatPipe implements PipeTransform {

  transform(value_: any, tableDsc_: TableDescription, idx_: number, digits_: number = 2): unknown {
    let cols = value_ == null || tableDsc_ == null ? null : tableDsc_.getActiveColumns();
    let field = cols == null || cols.length < idx_ + 1 ? null : cols[idx_];
    if (field === '_id' && isDocumentId(value_) && typeof value_ !== 'string') {
      return `ObjectId(${value_.$oid})`;
    }
    let format = field == null || tableDsc_.fieldMap == null ? null : tableDsc_.fieldMap[field];
    if (format == null) {
      return value_;
    }
    switch (format) {
      case 'double':
        let dVal = isNaN(value_) ? value_ : (+value_).toFixed(digits_);
        return dVal;
      default:
        return value_;
    }
  }

}
