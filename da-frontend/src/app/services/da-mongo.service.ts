import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, catchError, finalize, map, Observable, of, Subject, take } from 'rxjs';
import { environment } from 'src/environments/environment';
import { DaBusService } from './da-bus.service';
import { ConfigData, DaConfigService } from './da-config.service';
import { DaLogService } from './da-log.service';
import { DaMsgService, MessageLevel } from './da-msg.service';

@Injectable({
  providedIn: 'root'
})
export class DaMongoService {

  private _data?: TableDescription;
  public data = new Subject<TableDescription>();
  public isLoading = new BehaviorSubject<boolean>(false);

  private displayMap: Map<string, string[]> = new Map();
  private queryMap: Map<string, QueryParameters> = new Map();
  private decimalDigits = 3;

  constructor(
    private http: HttpClient,
    private log: DaLogService,
    private bus: DaBusService,
    private msg: DaMsgService,
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
      map.set('select', JSON.stringify(params.select));
    }
    if (params.sort && params.sort.length > 0) {
      map.set('sort', JSON.stringify(params.sort));
    }
    if (params.highlight) {
      map.set('highlight', params.highlight);
    }
    return map;
  }

  public pocessWebQuery(query_: any): void {
    if (query_) {
      let db = query_['db'];
      let table = query_['table'];
      if (table) {
        this.log.log(`web query table: ${table}`);
        this.config.getConfig(db).pipe(take(1)).subscribe(cfg_ => {
          if (cfg_ != null) {
            this.initializeConfig(db, cfg_);
          }
          //     });
          //    this.getDatabase().pipe(take(1)).subscribe(db_ => {
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
            let sort = JSON.parse(sortStr);
            params.sort = sort;
          }
          let selectStr = <string>query_['select'];
          if (selectStr && selectStr.indexOf(':') >= 0) {
            let select = JSON.parse(selectStr);
            params.select = select;
          }
          let highlight = query_['highlight'];
          if (highlight) {
            params.highlight = highlight;
          }
          this.getTables(db);
          this.getTableData(db, table, params);
          this.bus.isTopVisible.next(false);
          //          this.getTableData(db_.dbName, table, params);
        });
      }
    }
  }

  public connect(cstr_: string): Observable<DatabaseDescription | null> {
    this.isLoading.next(true);
    let body = { 'cstr': cstr_ };
    let httpParams = new HttpParams();
    httpParams = httpParams.set('cstr', cstr_);
    let url = `${environment.server}connect`;
    let response = this.http.post<DatabaseDescription>(url, body, { params: httpParams }).pipe(
      map(resp_ => {
        this.msg.publish({ level: MessageLevel.Default, text: `Connected '${resp_.dbName}'` });
        return resp_;
      }),
      catchError(err_ => {
        this.log.log(err_);
        this.msg.publish({ level: MessageLevel.Error, text: `Error Connecting` });
        return of(null);
      }),
      finalize(() => this.isLoading.next(false))
    );
    return response;
  }

  // public getDatabase(): Observable<DatabaseDescription> {
  //   let url = `${environment.server}database`;
  //   let response = this.http.get<DatabaseDescription>(url);
  //   response.subscribe(db_ => {
  //     this.config.config.subscribe(cfg_ => {
  //       this.initializeConfig(db_.dbName, cfg_);
  //     });
  //   })
  //   return response;
  // }

  // public getTables(db_: string): Observable<DatabaseDescription | null> {
  public getTables(db_: string): void {
    this.isLoading.next(true);
    let url = `${environment.server}tables/${db_}`;
    let response = this.http.get<DatabaseDescription>(url).pipe(
      map(resp_ => {
        this.msg.publish({ level: MessageLevel.Default, text: `Tables loaded` });
        this.bus.db.next(resp_);
        return resp_;
      }),
      catchError(err_ => {
        this.log.log(err_);
        var msg = err_?.error?.error != null ? err_.error.error : 'Error getting Tables';
        this.msg.publish({ level: MessageLevel.Error, text: msg });
        return of(null);
      }),
      finalize(
        () => this.isLoading.next(false)
      )
    ).subscribe();
    //   return response;
  }

  public getDecimalDigits(): number {
    return this.decimalDigits;
  }

  public setDecimalDigits(val_: number): void {
    this.decimalDigits = val_;
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
    let isSkipStatus = this.isLoading.getValue();
    if (!isSkipStatus) {
      this.isLoading.next(true);
    }
    let httpParams = new HttpParams();
    if (queryParams_) {
      httpParams = httpParams.set('page', queryParams_.page + "");
      httpParams = httpParams.set('page_size', queryParams_.pageSize + "");
      if (queryParams_.sort && queryParams_.sort.length > 0) {
        // NOTE: Multi sort through URL not GUI (20211228)
        let sortArray: { key: string, dir: string }[] = [];
        queryParams_.sort.forEach(sort_ => sortArray.push({ key: sort_.key, dir: sort_.dir }));
        let sortJson = JSON.stringify(sortArray);
        httpParams = httpParams.set('sort', sortJson);
      }
      if (queryParams_.select && queryParams_.select.length > 0) {
        // NOTE: Prepare for multi select but not in use yet (20211223)
        let selectArray: { key: string, op: string, val?: string | number }[] = [];
        queryParams_.select.forEach(select_ => {
          selectArray.push(select_.val != null ? { key: select_.key, op: select_.op, val: select_.val } : { key: select_.key, op: select_.op });
          httpParams = httpParams.set('select_key', select_.key);
          httpParams = httpParams.set('select_op', select_.op);
          if (select_.val != null) {
            httpParams = httpParams.set('select_val', select_.val);
          }
        });
        let selectJson = JSON.stringify(selectArray);
        httpParams = httpParams.set('select', selectJson);
      }
    }
    httpParams = httpParams.set('count_total', true);

    let url = `${environment.server}data/${db_}/${table_}`;
    let response = this.http.get<TableDescriptionWrapper>(url, { params: httpParams });
    response.pipe(
      map(data_ => {
        let data = Object.assign(new TableDescriptionImpl(), data_.data);
        if (data_.data.fieldMap != null) {
          data.headers = Object.getOwnPropertyNames(data_.data.fieldMap);
        }
        let cols = this.displayMap.get(data.table);
        if (cols && cols.length > 0) {
          data.setActiveColumns(cols);
        }
        let params = new QueryParameters(data);
        if (queryParams_) {
          params.highlight = queryParams_.highlight;
        }
        this.queryMap.set(table_, params);

        this._data = data;
        this.data.next(this._data);
      }),
      catchError(err_ => {
        this.log.log(err_);
        var msg = err_?.error?.error != null ? err_.error.error : 'Error getting Data';
        this.msg.publish({ level: MessageLevel.Error, text: msg });
        return of([]);
      }),
      finalize(() => { if (!isSkipStatus) { this.isLoading.next(false) }; })
    ).subscribe();
  }

  public saveRecord(db_: string, table_: string, data_: any): Observable<boolean> {
    let url = `${environment.server}save/${db_}/${table_}`;
    let response = this.http.put<{ 'result': boolean }>(url, data_);
    return response.pipe(map((data) => data['result']));
  }

  public deleteRecord(db_: string, table_: string, id_: string): Observable<boolean> {
    let url = `${environment.server}delete/${db_}/${table_}/${id_}`;
    let response = this.http.delete<any>(url);
    return response.pipe(map((data) => data['result']));
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
  }

  public hideColumn(col_: string): void {
    if (!this._data || !col_)
      return;
    let cols = [...this._data.getActiveColumns()];
    cols = cols.filter(c_ => col_ !== c_);
    this._data.setActiveColumns(cols);
    this.displayMap.set(this._data.table, cols);
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
      if (dsc_.select) {
        this.select = dsc_.select;
      }
      if (dsc_.sort) {
        this.sort = dsc_.sort;
      }
    }
  }

  page: number = 1;
  pageSize: number = 50;
  select?: { key: string, op: string, val?: string | number }[];
  sort?: { key: string, dir: string }[];
  highlight?: string;
}

export interface DatabaseDescription {
  dbName: string;
  tables: Table[];
  decimaldigits: number;
}

export interface Table {
  name: string;
  fields: string[];
  types: string[];
  fieldMap: {};
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
  fieldMap: { [key: string]: string };
  headers: string[];
  data: any[][];
  dataSize: number;
  querySize: number;
  pageSize: number;
  page: number;
  select?: { key: string, op: string, val?: string | number }[];
  sort?: { key: string, dir: string }[];

  getHiddenColumns(): string[];
  getActiveColumns(): string[];
  setActiveColumns(cols_: string[]): void;
  getHeaders(): string[];
  getData(): any[][];
  getStyleClass(i_: number): string | undefined;
  getType(header_: string): string | undefined;

}

export class TableDescriptionImpl implements TableDescription {
  public db!: string;
  public table!: string;
  public fieldMap!: { [key: string]: string };
  public headers!: string[];
  public data!: any[][];
  public dataSize!: number;
  public querySize!: number;
  public pageSize!: number;
  public page!: number;
  public select?: { key: string, op: string, val?: string | number }[];
  public sort?: { key: string, dir: string }[];
  private _activeColumns?: string[];

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

  public getType(header_: string): string | undefined {
    if (header_ == null || this.fieldMap == null)
      return undefined;
    let type = this.fieldMap[header_];
    return type;
  }

  public getStyleClass(i_: number): string | undefined {
    let field = this._activeColumns && this._activeColumns.length > 0 ? this._activeColumns[i_] : this.headers[i_];
    let style = this.getType(field);
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