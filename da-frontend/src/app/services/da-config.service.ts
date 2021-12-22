import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, ReplaySubject, Subject } from 'rxjs';
import { environment } from 'src/environments/environment';
import { DaLogService } from './da-log.service';

@Injectable({
  providedIn: 'root'
})
export class DaConfigService {

  public config: Subject<ConfigData> = new ReplaySubject(1);

  constructor(
    private log: DaLogService,
    private http: HttpClient) {
    this.getConfig();
  }

  public getConfig(): Observable<ConfigData> {
    let url = `${environment.server}config/da-config.json`;
    let response = this.http.get<ConfigData>(url);
    response.subscribe(rsp_ => {
      this.config.next(rsp_);
      this.log.log(`config: ${JSON.stringify(rsp_)}`);
    });
    return response;
  }
}

export interface ConfigData {
  database: [{
    id: string,
    tables: [
      {
        id: string,
        configs: [{
          id: string,
          columns: string[],
          sort: [{ col: string, dir: string }]
        }]
      }
    ]
  }];
}
