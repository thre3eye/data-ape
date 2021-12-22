import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { environment } from 'src/environments/environment';
import { ConfigData, DaConfigService } from './da-config.service';
import { DaLogService } from './da-log.service';

@Injectable({
  providedIn: 'root'
})
export class DaMongoService {

  private _data?: TableDescription;
  public data = new Subject<TableDescription>();
  // private _hiddenColMap: Map<string, string[]> = new Map();
  // public hiddenColumnMap = new Subject<Map<string, string[]>>();

  private pageSize: number = 50;
  private page: number = 1;
  private selectKey?: string;
  private selectOp?: string;
  private selectVal?: string;
  private sortKey?: string;
  private sortDir?: string;
  private displayMap: Map<string, string[]> = new Map();

  constructor(
    private http: HttpClient,
    private log: DaLogService,
    private config: DaConfigService) {
  }

  private initializeConfig(db_: string, cfg_: ConfigData): void {
    let dbCfg = cfg_.database.find(d_ => db_ === d_.id);
    if (dbCfg) {
      dbCfg.tables.forEach(tbl_ => {
        tbl_.configs.forEach(tCfg_ => {
          if ('default' === tCfg_.id) {
            let cols = tCfg_.columns;
            if (cols && cols.length > 0) {
              this.displayMap.set(tbl_.id, cols);
            } else {
              this.displayMap.delete(tbl_.id);
            }
          }
        })
      });
    }
  }

  public getDatabase(): Observable<DatabaseDescription> {
    let url = `${environment.server}database`;
    let response = this.http.get<DatabaseDescription>(url);
    response.subscribe(db_ => {
      this.config.config.subscribe(cfg_ => {
        this.initializeConfig(db_.dbName, cfg_);
      });
    })
    return response;
  }

  public getTables(db_: string): Observable<Tables> {
    let url = `${environment.server}tables/${db_}`;
    let response = this.http.get<Tables>(url);
    return response;
  }

  public getTableData(db_: string, table_: string): void {
    let params = new HttpParams();
    params = params.set('page', this.page);
    params = params.set('page_size', this.pageSize);
    params = params.set('count_total', true);
    if (this.selectKey && this.selectOp && this.selectVal) {
      params = params.set('select_key', this.selectKey);
      params = params.set('select_op', this.selectOp);
      params = params.set('select_val', this.selectVal);
    }
    if (this.sortKey && this.sortDir) {
      params = params.set('sort_key', this.sortKey);
      params = params.set('sort_dir', this.sortDir);
    }

    let url = `${environment.server}data/${db_}/${table_}`;
    let response = this.http.get<TableDescriptionWrapper>(url, { params: params });

    response.subscribe(data_ => {
      let data = Object.assign(new TableDescriptionImpl(), data_.data);

      let cols = this.displayMap.get(data.table);
      if (cols && cols.length > 0) {
        data.setActiveColumns(cols);
      }
      this._data = data;
      this.data.next(this._data);
    });
  }

  public setPaging(page_: number, pageSize_: number): void {
    if (this.page == page_ && this.pageSize == pageSize_)
      return;
    this.page = page_;
    this.pageSize = pageSize_;
  }

  public setSelect(key_?: string, op_?: string, val_?: string): void {
    if (!key_) {
      this.selectKey = undefined;
      this.selectOp = undefined;
      this.selectVal = undefined;
    } else {
      this.selectKey = key_;
      this.selectOp = op_;
      this.selectVal = val_;
    }
  }

  public setSort(key_?: string, dir_?: string): void {
    if (!key_) {
      this.sortKey = undefined;
      this.sortDir = undefined;
    } else {
      this.sortKey = key_;
      this.sortDir = dir_;
    }
  }

  // public setHiddenColumns(table_: string, cols_: Set<string>): void {
  //   if (!table_)
  //     return;
  //   if (!cols_ || cols_.size == 0) {
  //     this._hiddenColMap.delete(table_);
  //   } else {
  //     this._hiddenColMap.set(table_, [...cols_]);
  //   }
  //   this.hiddenColumnMap.next(this._hiddenColMap);
  // }

  public swapColumns(srcKey_: string, dstKey_: string): void {
    if (!this._data || !srcKey_ || !dstKey_)
      return;
    let cols = [...this._data.getActiveColumns()];
    let srcIdx = cols.indexOf(srcKey_);
    let dstIdx = cols.indexOf(dstKey_);
    this.log.log(`swapColumns: ${srcKey_}/${srcIdx} -> ${dstKey_}/${dstIdx}`);
    let srcVal = cols[srcIdx];
    let dstVal = cols[dstIdx];
    if (dstVal) {
      cols[srcIdx] = dstVal;
    }
    if (srcVal) {
      cols[dstIdx] = srcVal;
    }
    this._data.setActiveColumns(cols);
    this.displayMap.set(this._data.table, cols);
    //  this.data.next(this._data);
  }

  public hideColumn(col_: string): void {
    if (!this._data || !col_)
      return;
    let cols = [...this._data.getActiveColumns()];
    cols = cols.filter(c_ => col_ !== c_);
    this._data.setActiveColumns(cols);
    this.displayMap.set(this._data.table, cols);
    //  this.data.next(this._data);
  }

  public viewColumn(col_?: string): void {
    if (!this._data)
      return;
    let cols: string[];
    if (!col_) {
      cols = [...this._data.getActiveColumns()];
      let missing = this._data.headers.filter(c_ => cols.indexOf(c_) < 0);
      cols = [...missing, ...cols];
      //  let headers = [this._data.headers];
    } else {
      cols = this._data.getActiveColumns();
      let idx = cols.indexOf(col_);
      if (idx >= 0)
        return;
      idx = this._data.headers.indexOf(col_);
      if (idx < 0)
        return;
      cols = [col_, ...cols];
      //  this.data.next(this._data);
    }
    this._data.setActiveColumns(cols);
    this.displayMap.set(this._data.table, cols);
  }

}

export interface DatabaseDescription {
  dbName: string;
}

export interface Tables {
  tables: string[];
}

export interface TableData {
  data: Map<string, string[]>;
}

export interface TableDescription {
  db: string;
  table: string;
  headers: string[];
  types: string[];
  data: any[][];
  dataSize: number;
  querySize: number;
  pageSize: number;
  page: number;

  getHiddenColumns(): string[];
  getActiveColumns(): string[];
  setActiveColumns(cols_: string[]): void;
  getHeaders(): string[];
  getData(): any[][];
  getStyleClass(i_: number): string;
}

export class TableDescriptionImpl implements TableDescription {
  public db!: string;
  public table!: string;
  public headers!: string[];
  public types!: string[];
  public data!: any[][];
  public dataSize!: number;
  public querySize!: number;
  public pageSize!: number;
  public page!: number;
  private _activeColumns?: string[];
  private _activeStyles?: string[]; // Note: For speed

  public getHiddenColumns(): string[] {
    const active = this._activeColumns;
    if (active && active.length > 0) {
      let cols = [...this.headers];
      cols = cols.filter(c_ => active.indexOf(c_) < 0);
      return cols;
    } else {
      return [];
    }
  }

  public getStyleClass(i_: number): string {
    let styles = this._activeStyles&&this._activeStyles.length>0?this._activeStyles: this.types;
    let style = styles[i_];
    return style;
  }

  public getActiveColumns(): string[] {
    let cols = this._activeColumns;
    if (!cols) {
      cols = [...this.headers];
    }
    return cols;
  }
  public setActiveColumns(cols_: string[]): void {
    this._activeColumns = cols_;
    let styles: string[] = [];
    this._activeColumns.forEach(c_ => {
      let idx = this.headers.indexOf(c_);
      if (idx >= 0) {
        styles.push(this.types[idx]);
      }
    });
    this._activeStyles = styles;
  }

  public getHeaders(): string[] {
    if (this._activeColumns && this._activeColumns.length > 0) {
      let headers: string[] = [];
      this._activeColumns.forEach(col_ => {
        let idx = this.headers.indexOf(col_);
        if (idx >= 0) {
          headers.push(col_);
        }
      });
      return headers;
    } else {
      return this.headers;
    }
  }

  public getData(): any[][] {
    if (this._activeColumns && this._activeColumns.length > 0) {
      let data: any[][] = [];
      this._activeColumns.forEach(col_ => {
        let idx = this.headers.indexOf(col_);
        if (idx >= 0) {
          data.push(this.data[idx]);
        }
      });
      return data;
    } else {
      return this.data;
    }
  }

}

export interface TableDescriptionWrapper {
  data: TableDescription;
}