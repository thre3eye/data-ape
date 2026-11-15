import { Injectable, Pipe, PipeTransform } from '@angular/core';
import { TableDescription } from './da-mongo.service';

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
