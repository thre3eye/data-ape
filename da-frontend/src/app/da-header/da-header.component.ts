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
  public select: { key: string, op: string, val?: string | number }[] = [{ key: '__all__', op: 'eq' }];
  public sort: { key: string, dir: string }[] = [{ key: '__none__', dir: '1' }];
  // public sortKey: string = '__none__';
  // public sortDir: string = '1';

  public isSlim: boolean = true;

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
        this.sort = [...params.sort];
        if (params.sort.length > 1) {
          this.isSlim = false;
        }
      } else {
        this.sort = [{ key: '__none__', dir: '1' }];
      }
      if (params && params.select && params.select.length > 0) {
        this.select = [...params.select];
        if (params.select.length > 1) {
          this.isSlim = false;
        }
      } else {
        this.select = [{ key: '__all__', op: 'eq' }];
      }
    });
  }

  public addSelect(idx_: number): void {
    if (idx_ == 0) {
      if ('__all__' !== this.select[this.select.length - 1].key) {
        this.select.push({ key: '__all__', op: 'eq' });
      }
    } else {
      this.select.splice(idx_, 1);
    }
    this.isSlim = this.select.length == 1 && this.sort.length == 1;
  }

  public addSort(idx_: number): void {
    if (idx_ == 0) {
      if ('__none__' !== this.sort[this.sort.length - 1].key) {
        this.sort.push({ key: '__none__', dir: '1' });
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
    let isSelect = this.select && this.select.length > 0 && '__all__' !== this.select[0].key;
    if (isSelect) {
      let select: { key: string, op: string, val?: string | number }[] = [];
      this.select.forEach(sel_ => {
        if ('__all__' !== sel_.key) {
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
    let isSort = this.sort && this.sort.length > 0 && '__none__' !== this.sort[0].key;
    if (isSort) {
      let sort: { key: string, dir: string }[] = [];
      this.sort.forEach(srt_ => {
        if ('__none__' !== srt_.key) {
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
