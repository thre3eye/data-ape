import { Component, OnInit } from '@angular/core';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, TableData, TableDescription } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-data',
  templateUrl: './da-data.component.html',
  styleUrls: ['./da-data.component.scss']
})
export class DaDataComponent implements OnInit {

  public headers: string[] = [];
  public data?: TableDescription;
  public size: number = 0;

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) { }

  public getData(i_: number, j_: number): any {
    if (!this.data || !this.data.data)
      return null;
    //   this.log.log(`index ${i_}/${j_}`);
    let value = this.data.data[i_][j_];
    return value;
  }

  public getStyleClass(i_: number): string {
    if (!this.data || !this.data.types)
      return '';
    let value = this.data?.types[i_];
    return value;
  }

  public select(idx_: number): void {
    this.log.log(`idx: ${idx_}`);
  }

  ngOnInit(): void {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_)
        return;
      this.data = data_;// JSON.stringify(data_.data);
      //     this.log.log(data_);
      //   this.headers = Object.getOwnPropertyNames(data_.data);// [...data_.data.keys()]
      //   this.size = (data_.data)[this.headers[0]].length;
      this.log.log(this.headers);
    })
  }

}
