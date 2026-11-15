import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Location } from '@angular/common';
import { environment } from 'src/environments/environment';
import { DaLogService } from './services/da-log.service';
import { DaMongoService } from './services/da-mongo.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent implements OnInit {

  title = 'data-ape';

  constructor(
    private route: ActivatedRoute,
    private location: Location,
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_ || !data_.table)
        return;
      let urlMap = this.mongoDb.getWebQueryMap();
      if (!urlMap)
        this.location.replaceState('/');
      else {
        let pre = '?';
        let url = '/';
        urlMap.forEach((val_, key_) => {
          url = `${url}${pre}${key_}=${val_}`;
          pre = '&';
        });
        this.location.replaceState(url);
      }
    });
    // this.mongoDb.query.subscribe(query_ => {
    //   let url = '/';
    //   if (query_ != null && query_.length > 0) {
    //     url = `/?query=${decodeURI(query_.replace(/\s/g, ''))}`;
    //   }
    // //  window.location.href = url;//assign(url);
    //  this.location.replaceState(url);
    // });
  }

  ngOnInit(): void {
    this.route.queryParams.subscribe(params_ => {
      if (!environment.production) {
        this.log.log(`params: ${JSON.stringify(params_)} - ${params_['table']}`);
      }
      if (!params_ || Object.keys(params_).length == 0)
        return;
      this.mongoDb.pocessWebQuery(params_);
    });
  }
}
