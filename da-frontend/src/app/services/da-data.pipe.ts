import { Pipe, PipeTransform } from '@angular/core';
import { TableDescription } from './da-mongo.service';

@Pipe({
  name: 'daData'
})
export class DaDataPipe implements PipeTransform {

  transform(data_?: TableDescription, ...args_: number[]): unknown {
    if (!data_ || !args_ || args_.length < 2) {
      return null;
    }
    let x = args_[0];
    let y = args_[1];
    let value = data_.data[x][y];
    if (value == null)
      return value;
    let type = data_.types[x];
    switch (type) {
      case 'Double':
        if (!isNaN(value)) {
          value = (+value).toFixed(2);
        }
        break;
      case 'ArrayList':
      case 'Document':
        value = JSON.stringify(value);
        break;
    }
    return value;
  }

}
