import { Component, OnInit } from '@angular/core';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, TableDescription } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-header',
  templateUrl: './da-header.component.html',
  styleUrls: ['./da-header.component.scss']
})
export class DaHeaderComponent implements OnInit {

  public data?: TableDescription;
  public selectKey: string = '__all__';
  public selectOp: string = 'eq';
  public selectVal?: string;
  public sortKey: string = '__none__';
  public sortDir: string = '1';

  public findOperators: string[][] = [
    ['>', 'gt'],
    ['>=', 'gte'],
    ['=', 'eq'],
    ['<=', 'lte'],
    ['<', 'lt'],
    ['starts', 'stw'],
    ['contains', 'ctn']
  ];

  public sortOperators: string[][] = [
    ['asc', '1'],
    ['desc', '-1']
  ];

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_ || data_.querySize < 0)
        return;
      this.data = data_;
      if (this.data.sortKey && this.data.sortDir) {
        this.sortKey = this.data.sortKey;
        this.sortDir = this.data.sortDir;
      }
    });
  }

  public submit(): void {
    if (!this.data)
      return;
    this.log.log(`Select key: ${this.selectKey}`);
    this.log.log(`Select op : ${this.selectOp}`);
    this.log.log(`Select val: ${this.selectVal}`);

    this.log.log(`Sort   key:  ${this.sortKey}`);
    this.log.log(`Sort   dir:  ${this.sortDir}`);

    let isSelect = !this.selectKey || '__all__' !== this.selectKey;
    let selectKey = isSelect ? this.selectKey : undefined;
    let selectOp = isSelect ? this.selectOp : undefined;
    let selectVal = isSelect ? this.selectVal : undefined;
    this.mongoDb.setSelect(selectKey, selectOp, selectVal);

    let isSort = !this.sortKey || '__none__' !== this.sortKey;
    let sortKey = isSort ? this.sortKey : undefined;
    let sortDir = isSort ? this.sortDir : undefined;
    this.mongoDb.setSort(this.data.table, sortKey, sortDir);

    this.mongoDb.getTableData(this.data.db, this.data.table);
  }

  ngOnInit(): void {
  }

}
