import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, Subject, take } from 'rxjs';
import { environment } from 'src/environments/environment';
import { ConfigData, DaConfigService } from './da-config.service';
import { DaLogService } from './da-log.service';

@Injectable({
  providedIn: 'root'
})
export class DaMongoService {

  private _data?: TableDescription;
  public data = new Subject<TableDescription>();

  private displayMap: Map<string, string[]> = new Map();
  private queryMap: Map<string, QueryParameters> = new Map();

  constructor(
    private http: HttpClient,
    private log: DaLogService,
    private config: DaConfigService) {
  }

  private initializeConfig(db_: string, cfg_: ConfigData): void {
    let dbCfg = cfg_.database.find(d_ => db_ === d_.id);
    if (dbCfg) {
      dbCfg.tables.forEach(tbl_ => {
        tbl_.configs.forEach(tblCfg_ => {
          if ('default' === tblCfg_.id) {
            let params = this.queryMap.get(tbl_.id) ?? new QueryParameters();
            this.queryMap.set(tbl_.id, params);

            let tblCols = tblCfg_.columns;
            if (tblCols && tblCols.length > 0) {
              this.displayMap.set(tbl_.id, tblCols);
            } else {
              this.displayMap.delete(tbl_.id);
            }
            let tblSort = tblCfg_.sort;
            if (tblSort && tblSort.length > 0) {
              params.sort = [];
              tblSort.forEach(s_ => params.sort?.push({ key: s_.col, dir: s_.dir }));
            } else {
              params.sort = undefined;
            }
          }
        })
      });
    }
  }

  public getWebQueryMap(): Map<string, string> | undefined {
    if (!this._data)
      return undefined;
    let map = new Map<string, string>();
    map.set('db', this._data?.db);
    map.set('table', this._data.table);
    let params = this.queryMap.get(this._data.table);
    if (!params)
      return undefined;
    map.set('page', params.page + '');
    map.set('pageSize', params.pageSize + '');
    if (params.select && params.select.length > 0) {
      let select = params.select[0];
      let selectStr = `${select.key}:${select.op}`;
      if (select.val) {
        selectStr = `${selectStr}:${select.val}`;
      }
      map.set('select', selectStr);
    }
    if (params.sort && params.sort.length > 0) {
      let sort = params.sort[0];
      map.set('sort', `${sort.key}:${sort.dir}`);
    }
    if (params.highlight) {
      map.set('highlight', params.highlight);
    }
    return map;
  }

  public pocessWebQuery(query_: any): void {
    if (query_) {
      let table = query_['table'];
      if (table) {
        //     let db = query_['db'];
        this.log.log(`web query table: ${table}`);
        this.getDatabase().pipe(take(1)).subscribe(db_ => {
          let params = this.getQueryParameters(table);
          let page = query_['page'];
          if (page) {
            params.page = page;
          }
          let pageSize = query_['pageSize'];
          if (pageSize) {
            params.pageSize = pageSize;
          }
          let sortStr = <string>query_['sort'];
          if (sortStr && sortStr.indexOf(':') >= 0) {
            let sort = sortStr.split(':');
            params.sort = [{ key: sort[0], dir: sort[1] }];
          }
          let selectStr = <string>query_['select'];
          if (selectStr && selectStr.indexOf(':') >= 0) {
            let select = selectStr.split(':');
            params.select = [{ key: select[0], op: select[1], val: select.length > 2 ? select[2] : undefined }];
          }
          let highlight = query_['highlight'];
          if (highlight) {
            params.highlight = highlight;
          }
          this.getTableData(db_.dbName, table, params);
        });
      }
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

  public getQueryParameters(table_: string): QueryParameters {
    let params = this.queryMap.get(table_);
    if (!params) {
      params = new QueryParameters();
      this.queryMap.set(table_, params);
    }
    return params;
  }

  public getTableData(db_: string, table_: string, queryParams_?: QueryParameters): void {
    let httpParams = new HttpParams();
    if (queryParams_) {
      httpParams = httpParams.set('page', queryParams_.page + "");
      httpParams = httpParams.set('page_size', queryParams_.pageSize + "");
      if (queryParams_.sort && queryParams_.sort.length > 0) {
        // NOTE: Prepare for multi sort but not in use yet (20211223)
        queryParams_.sort.forEach(sort_ => {
          httpParams = httpParams.set('sort_key', sort_.key);
          httpParams = httpParams.set('sort_dir', sort_.dir);
        });
      }
      if (queryParams_.select && queryParams_.select.length > 0) {
        // NOTE: Prepare for multi select but not in use yet (20211223)
        queryParams_.select.forEach(select_ => {
          httpParams = httpParams.set('select_key', select_.key);
          httpParams = httpParams.set('select_op', select_.op);
          if (select_.val) {
            httpParams = httpParams.set('select_val', select_.val);
          }
        });
      }
    }
    httpParams = httpParams.set('count_total', true);

    let url = `${environment.server}data/${db_}/${table_}`;
    let response = this.http.get<TableDescriptionWrapper>(url, { params: httpParams });

    response.subscribe(data_ => {
      let data = Object.assign(new TableDescriptionImpl(), data_.data);

      let cols = this.displayMap.get(data.table);
      if (cols && cols.length > 0) {
        data.setActiveColumns(cols);
      }

      let params = new QueryParameters(data);
      if (queryParams_) {
        params.select = queryParams_.select;
        params.highlight = queryParams_.highlight;
      }
      this.queryMap.set(table_, params);

      this._data = data;
      this.data.next(this._data);
    });
  }

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

export class QueryParameters {

  constructor(dsc_?: TableDescription) {
    if (dsc_) {
      this.page = dsc_.page;
      this.pageSize = dsc_.pageSize;
      if (dsc_.sortKey && dsc_.sortDir) {
        this.sort = [{ key: dsc_.sortKey, dir: dsc_.sortDir }];
      }
    }
  }

  page: number = 1;
  pageSize: number = 50;
  select?: { key: string, op: string, val?: string }[];
  sort?: { key: string, dir: string }[];
  highlight?: string;
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
  sortKey?: string;
  sortDir?: string;

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
  public sortKey?: string;
  public sortDir?: string;
  private _activeColumns?: string[];
  private _activeStyles?: string[]; // Note: Pre-selected to speed up table

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
    let styles = this._activeStyles && this._activeStyles.length > 0 ? this._activeStyles : this.types;
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