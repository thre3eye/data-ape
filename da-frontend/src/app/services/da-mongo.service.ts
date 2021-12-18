import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class DaMongoService {

  public data = new Subject<TableDescription>();

  private table: string = '';
  private pageSize: number = 50;
  private page: number = 1;

  constructor(private http: HttpClient) {
    this.connect();
  }

  private async connect(): Promise<void> {
    return Promise.resolve();
  }

  public getTables(): Observable<Tables> {
    let response = this.http.get<Tables>(`http://localhost:4100/tables/at`);
    return response;
  }

  public getTableData(table_: string): Observable<TableDescriptionWrapper> {
    this.table = table_;

    let params = new HttpParams();
    params = params.set('page', this.page);
    params = params.set('page_size', this.pageSize);
    params = params.set('sort', 'ts_trd');
    params = params.set('sort_dir', -1);
    params = params.set('count_total', true);
    let response = this.http.get<TableDescriptionWrapper>(`http://localhost:4100/data/at/${table_}`, { params: params });

    response.subscribe(data_ => this.data.next(data_?.data));
    return response;
  }

  public setPageAndSize(page_: number, pageSize_: number): void {
    if (this.page == page_ && this.pageSize == pageSize_)
      return;
    this.page = page_;
    this.pageSize = pageSize_;
    this.getTableData(this.table);
  }

}

export interface Tables {
  tables: string[];
}

export interface TableData {
  data: Map<string, string[]>;
}

export interface TableDescription {
  table: string;
  headers: string[];
  types: string[];
  data: any[][];
  querySize: number;
  pageSize: number;
  page: number;
}

export interface TableDescriptionWrapper {
  data: TableDescription;
}