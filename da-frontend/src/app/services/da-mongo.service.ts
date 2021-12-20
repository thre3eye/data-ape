import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { environment } from 'src/environments/environment';

@Injectable({
  providedIn: 'root'
})
export class DaMongoService {

  public data = new Subject<TableDescription>();

  private pageSize: number = 50;
  private page: number = 1;
  private selectKey?: string;
  private selectOp?: string;
  private selectVal?: string;
  private sortKey?: string;
  private sortDir?: string;

  constructor(private http: HttpClient) {
    this.connect();
  }

  private async connect(): Promise<void> {
    return Promise.resolve();
  }

  public getDatabase(): Observable<DatabaseDescription> {
    let url = `${environment.server}database`;
    let response = this.http.get<DatabaseDescription>(url);
    return response;
  }

  public getTables(db_: string): Observable<Tables> {
    let url = `${environment.server}tables/${db_}`;
    let response = this.http.get<Tables>(url);
    return response;
  }

  public getTableData(db_: string, table_: string): Observable<TableDescriptionWrapper> {
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

    response.subscribe(data_ => this.data.next(data_?.data));
    return response;
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
}

export interface TableDescriptionWrapper {
  data: TableDescription;
}