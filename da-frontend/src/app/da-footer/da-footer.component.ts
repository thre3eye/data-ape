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

  ngOnInit(): void {
  }

}
