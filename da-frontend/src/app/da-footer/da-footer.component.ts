import { Component, OnInit } from '@angular/core';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, TableDescription } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-footer',
  templateUrl: './da-footer.component.html',
  styleUrls: ['./da-footer.component.scss']
})
export class DaFooterComponent implements OnInit {

  public data?: TableDescription;
  public page: number = -1;
  public pageCount: number = -1;
  public pageSize: number = 50;

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_ || data_.querySize < 0)
        return;
      this.data = data_;

      this.page = data_.page;
      this.pageCount = Math.round(data_.querySize / data_.pageSize);
      this.pageSize = data_.pageSize;
    });
  }

  public setPaging(): void {
    this.log.log(`page: ${this.page}`);
    if (this.page < 1) {
      this.page = 1;
    } else if (this.page > this.pageCount) {
      this.page = this.pageCount;
    }
    //   this.page = this.pageSize;
    this.mongoDb.setPaging(this.page, this.pageSize);
    if (!this.data)
      return;
    this.mongoDb.getTableData(this.data.db, this.data.table);
  }

  public export(): void {
    if (!this.data)
      return;
    //  const replacer = (key_: any, value_: any) => value_ === null ? '' : value_; // Handle null values
    let csv = [
      this.data.headers.join(','),
      // ...this.data.data.join(',')
    ];//.join('\r\n');
    for (let rowIdx = 0; rowIdx < this.data.dataSize; rowIdx++) {
      let row = [];
      for (let colIdx = 0; colIdx < this.data.data.length; colIdx++) {
        row.push(this.data.data[colIdx][rowIdx]);
      }
      csv.push(row.join(','));
    }
    navigator.clipboard.writeText(csv.join('\r\n'))
      .then(() => this.log.log(`Copied to clipboard.`))
      .catch((err_) => this.log.log(`Err copying to clipboard: ${err_}`));
  }

  ngOnInit(): void {
  }

}
