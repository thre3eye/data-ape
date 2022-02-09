import { Component, OnInit } from '@angular/core';
import { environment } from 'src/environments/environment';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, QueryParameters, TableDescription } from '../services/da-mongo.service';
import { DaMsgService, MessageLevel } from '../services/da-msg.service';

@Component({
  selector: 'app-da-header',
  templateUrl: './da-header.component.html',
  styleUrls: ['./da-header.component.scss']
})
export class DaHeaderComponent implements OnInit {

  public readonly sortNull = { key: '__none__', dir: '1' };
  public readonly selectNull = { key: '__all__', op: 'eq' };

  public readonly selectNoVal = ['exst', 'nxst'];

  public data?: TableDescription;
  public headers: string[] = [];
  public select: { key: string, op: string, val?: string | number }[] = this.resetSelect();
  public sort: { key: string, dir: string }[] = this.resetSort();
  public isSlim: boolean = true;

  public queryStr: string | undefined;
  public sortStr: string | undefined;

  public findOperators: string[][] = [
    ['>', 'gt'],
    ['>=', 'gte'],
    ['=', 'eq'],
    ['!=', 'ne'],
    ['<=', 'lte'],
    ['<', 'lt'],
    ['starts', 'stw'],
    ['contains', 'ctn'],
    ['excludes', 'excl'],
    ['ends', 'endw'],
    ['exists', 'exst'],
    ['! exists', 'nxst'],
  ];

  public sortOperators: string[][] = [
    ['asc', '1'],
    ['desc', '-1']
  ];

  constructor(
    private log: DaLogService,
    private msg: DaMsgService,
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

  public onChangeSelect(idx_: number, val_: any, evt_?: KeyboardEvent): void {
    if (evt_ != null && evt_.key === 'Enter')
      return;
    this.log.log(`change: ${val_} - ${JSON.stringify(this.select[idx_])}`);
    this.generateQuery();
  }

  public onChangeSort(idx_: number, val_: any): void {
    this.log.log(`sort: ${val_} - ${JSON.stringify(this.sort[idx_])}`);
    this.generateQuery();
  }

  private generateQuery(): void {
    let selectData: string[] = [];
    for (let i = 0; i < this.select.length; i++) {
      let select = this.select[i];
      switch (select.op) {
        case 'eq':
        case 'gt':
        case 'gte':
        case 'lt':
        case 'lte':
        case 'ne':
          if (select.val != null) {
            let type = this.data?.getType(select.key);
            let val = 'string' === type ? `'${select.val}'` : select.val;
            selectData.push(`{${select.key}: {$${select.op}: ${val}}}`);
          }
          break;
        case 'exst':
        case 'nxst':
          selectData.push(`{${select.key}: {$exists: ${'exst' === select.op}}}`);
          break;
        case 'ctn':
          selectData.push(`{${select.key}: {$regex: /.*${select.val}.*/i}}`);
          break;
        case 'excl':
          selectData.push(`{${select.key}: {$regex: /^(?!.*${select.val}).*/i}}`);
          break;
        case 'endw':
          selectData.push(`{${select.key}: {$regex: /${select.val}$/i}}`);
          break;
        case 'stw':
          selectData.push(`{${select.key}: {$regex: /^${select.val}/i}}`);
          break;
      }
    }
    this.queryStr = selectData.length == 0
      ? undefined
      : selectData.length == 1
        ? selectData[0]
        : `{$and: [${selectData.join(',')}]}`;
    this.log.log(`query: ${this.queryStr}`);

    let sortData: string[] = [];
    for (let i = 0; i < this.sort.length; i++) {
      let sort = this.sort[i];
      if (this.sortNull.key === sort.key)
        continue;
      sortData.push(`${sort.key}: ${sort.dir}`);
    }
    this.sortStr = sortData.length == 0 ? undefined : `{${sortData.join(',')}}`;
    this.log.log(`sort: ${this.sortStr}`);
  }

  private resetSelect(): { key: string, op: string, val?: string | number | undefined }[] {
    this.queryStr = undefined;
    let select = Object.assign({}, this.selectNull);
    return [select];
  }

  private resetSort(): { key: string, dir: string }[] {
    this.sortStr = undefined;
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

  public copyQuery(): string | undefined {
    if (this.data == null)
      return undefined;
    this.generateQuery();
    let str = `db.getCollection('${this.data.table}').find(${this.queryStr ? this.queryStr : '{}'})`;
    if (this.sortStr) {
      str = `${str}.sort(${this.sortStr})`;
    }
    navigator.clipboard.writeText(str).then(() => this.msg.publish({ level: MessageLevel.Default, text: `Query copied` })).catch(e => this.log.log(e));
    return str;
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
          if (sel_.val && type && ['double', 'int', 'long'].indexOf(type) >= 0) {
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
