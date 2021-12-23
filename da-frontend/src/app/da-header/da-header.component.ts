import { Component, OnInit } from '@angular/core';
import { environment } from 'src/environments/environment';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, QueryParameters, TableDescription } from '../services/da-mongo.service';

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
      let params = this.mongoDb.getQueryParameters(this.data.table);
      if (params && params.sort && params.sort.length > 0) {
        let sort = params.sort[0];
        this.sortKey = sort.key;
        this.sortDir = sort.dir;
      } else {
        this.sortKey = '__none__';
        this.sortDir = '1';
      }
      if (params && params.select && params.select.length > 0) {
        let select = params.select[0];
        this.selectKey = select.key;
        this.selectOp = select.op;
        this.selectVal = select.val;
      } else {
        this.selectKey = '__all__';
        this.selectOp = 'eq';
        this.selectVal = undefined;
      }
    });
  }

  public submit(): void {
    if (!this.data)
      return;
    if (!environment.production) {
      this.log.log(`Select key: ${this.selectKey}`);
      this.log.log(`Select op : ${this.selectOp}`);
      this.log.log(`Select val: ${this.selectVal}`);
      this.log.log(`Sort   key:  ${this.sortKey}`);
      this.log.log(`Sort   dir:  ${this.sortDir}`);
    }

    let params: QueryParameters = this.mongoDb.getQueryParameters(this.data.table);
    let isSelect = !this.selectKey || !this.selectOp || '__all__' !== this.selectKey;
    params.select = isSelect ? [{ key: this.selectKey, op: this.selectOp, val: this.selectVal }] : undefined;
    let isSort = !this.sortKey || '__none__' !== this.sortKey;
    params.sort = isSort ? [{ key: this.sortKey, dir: this.sortDir }] : undefined;

    this.mongoDb.getTableData(this.data.db, this.data.table, params);
  }

  ngOnInit(): void {
  }

}
