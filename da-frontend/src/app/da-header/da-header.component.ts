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

  public readonly sortNull = { key: '__none__', dir: '1' };
  public readonly selectNull = { key: '__all__', op: 'eq' };

  public data?: TableDescription;
  public headers: string[] = [];
  public select: { key: string, op: string, val?: string | number }[] = this.resetSelect();
  public sort: { key: string, dir: string }[] = this.resetSort();

  public isSlim: boolean = true;

  public findOperators: string[][] = [
    ['>', 'gt'],
    ['>=', 'gte'],
    ['=', 'eq'],
    ['<=', 'lte'],
    ['<', 'lt'],
    ['starts', 'stw'],
    ['contains', 'ctn'],
    ['ends', 'edw']
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
      this.headers = data_.headers ? [...data_.headers].sort() : [];
      let params = this.mongoDb.getQueryParameters(this.data.table);
      let isSort = params && params.sort && params.sort.length > 0;
      this.sort = isSort && params.sort /* req'd to compile */ ? [...params.sort] : this.resetSort();
      let isSelect = params && params.select && params.select.length > 0;
      this.select = isSelect && params.select  /* req'd to compile */ ? [...params.select] : this.resetSelect();
      this.isSlim = this.select.length <= 1 && this.sort.length <= 1;
    });
  }

  private resetSelect(): { key: string, op: string, val?: string | number | undefined }[] {
    let select = Object.assign({}, this.selectNull);
    return [select];
  }

  private resetSort(): { key: string, dir: string }[] {
    let sort = Object.assign({}, this.sortNull);
    return [sort];
  }

  public addSelect(idx_: number): void {
    if (idx_ == 0) {
      if (this.selectNull.key !== this.select[this.select.length - 1].key) {
        this.select.push(this.resetSelect()[0]);
      }
    } else {
      this.select.splice(idx_, 1);
    }
    this.isSlim = this.select.length == 1 && this.sort.length == 1;
  }

  public addSort(idx_: number): void {
    if (idx_ == 0) {
      if (this.sortNull.key !== this.sort[this.sort.length - 1].key) {
        this.sort.push(this.resetSort()[0]);
      }
    } else {
      this.sort.splice(idx_, 1);
    }
    this.isSlim = this.select.length == 1 && this.sort.length == 1;
  }

  public submit(): void {
    if (!this.data)
      return;
    if (!environment.production) {
      this.log.log(`Select: ${JSON.stringify(this.select)}`);
      this.log.log(`Sort  :  ${JSON.stringify(this.sort)}`);
    }

    let params: QueryParameters = this.mongoDb.getQueryParameters(this.data.table);
    params.select = undefined;
    let isSelect = this.select && this.select.length > 0 && this.selectNull.key !== this.select[0].key;
    if (isSelect) {
      let select: { key: string, op: string, val?: string | number }[] = [];
      this.select.forEach(sel_ => {
        if (this.selectNull.key !== sel_.key) {
          let type = this.data?.getType(sel_.key);
          if (sel_.val && type && ['Double', 'Integer', 'Long'].indexOf(type) >= 0) {
            sel_.val = +sel_.val;
          }
          select.push(sel_);
        }
      });
      params.select = select;
    }

    params.sort = undefined;
    let isSort = this.sort && this.sort.length > 0 && this.sortNull.key !== this.sort[0].key;
    if (isSort) {
      let sort: { key: string, dir: string }[] = [];
      this.sort.forEach(srt_ => {
        if (this.sortNull.key !== srt_.key) {
          sort.push(srt_);
        }
      });
      params.sort = sort;
    }

    this.mongoDb.getTableData(this.data.db, this.data.table, params);
  }

  ngOnInit(): void {
  }

}
