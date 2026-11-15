import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { catchError, finalize, map, Observable, of, ReplaySubject, Subject } from 'rxjs';
import { environment } from 'src/environments/environment';
import { DaLogService } from './da-log.service';
import { DaMsgService, MessageLevel } from './da-msg.service';

@Injectable({
  providedIn: 'root'
})
export class DaConfigService {

  public config: Subject<ConfigData> = new ReplaySubject(1);

  constructor(
    private log: DaLogService,
    private http: HttpClient,
    private msg: DaMsgService) {
    // this.getConfig();
  }

  public getConfig(db_: string): Observable<ConfigData | null> {
    let url = `${environment.server}config/da-config.${db_}.json`;
    let response = this.http.get<ConfigData>(url).pipe(
      map(resp_ => {
        this.msg.publish({ level: MessageLevel.Default, text: `Config loaded '${db_}'` });
        return resp_;
      }),
      catchError(err_ => {
        this.log.log(err_);
        this.msg.publish({ level: MessageLevel.Error, text: `Error getting Config '${db_}'` });
        return of(null);
      }),
      finalize(() => { })
    );
    // response.subscribe(rsp_ => {
    //   this.config.next(rsp_);
    //   this.log.log(`config: ${JSON.stringify(rsp_)}`);
    // });
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
