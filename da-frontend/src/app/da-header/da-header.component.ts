import { ThrowStmt } from '@angular/compiler';
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
  public sortOp: string = 'asc';

  public findOperators: string[][] = [
    ['>', 'gt'],
    ['>=', 'gte'],
    ['=', 'eq'],
    ['<=', 'lte'],
    ['<', 'lt'],
    ['starts', 'stw'],
    ['contains', 'cnt']
  ];

  public sortOperators: string[][] = [
    ['asc', 'asc'],
    ['desc', 'desc']
  ];

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_ || data_.querySize < 0)
        return;
      this.data = data_;

      //    this.page = data_.page;
      //    this.pageCount = Math.round(data_.querySize / data_.pageSize);
      //    this.pageSize = data_.pageSize;
    });
  }

  // public setSelectKey(val_: string): void {
  //   this.log.log(`Select key: ${val_} - ${this.selectKey}`);
  // }

  // public setSelectValue(): void {
  //   this.log.log(`Select val: ${this.selectVal}`);
  // }

  // public setSortKey(val_: string): void {
  //   this.log.log(`Sort   key: ${val_} - ${this.sortKey}`);
  // }

  public submit(): void {
    if (!this.data)
      return;
    this.log.log(`Select key: ${this.selectKey}`);
    this.log.log(`Select op : ${this.selectOp}`);
    this.log.log(`Select val: ${this.selectVal}`);

    this.log.log(`Sort   key:  ${this.sortKey}`);
    this.log.log(`Sort   op :  ${this.sortOp}`);

    let isSelect = !this.selectKey || '__all__' === this.selectKey;
    let selectKey = isSelect ? this.selectKey : undefined;
    let selectOp = isSelect ? this.selectOp : undefined;
    let selectVal = isSelect ? this.selectVal : undefined;
    this.mongoDb.setSelect(selectKey, selectOp, selectVal);

    let isSort = !this.sortKey || '__none__' == this.sortKey;
    let sortKey = isSort ? this.sortKey : undefined;
    let sortOp = isSort ? this.sortOp : undefined;
    this.mongoDb.setSort(sortKey, sortOp);

    this.mongoDb.getTableData(this.data.table);
  }

  ngOnInit(): void {
  }

}
