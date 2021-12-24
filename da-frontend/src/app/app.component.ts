import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
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
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) { }

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
