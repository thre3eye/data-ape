import { Component, OnInit } from '@angular/core';
import { DaBusService } from '../services/da-bus.service';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-top',
  templateUrl: './da-top.component.html',
  styleUrls: ['./da-top.component.scss']
})
export class DaTopComponent implements OnInit {

  public isOpen: boolean = true;
  public cStr?: string;

  constructor(
    private log: DaLogService,
    private bus: DaBusService,
    private mongoDb: DaMongoService
  ) {
    bus.isTopVisible.subscribe(status_ => {
      this.isOpen = status_;
    })
  }

  public submit(): void {
    if (this.cStr == null || this.cStr.length == 0) {
      this.log.log(`Missing Connection string`);
      return;
    }
    this.bus.isTopVisible.next(false);
    this.mongoDb.connect(this.cStr).subscribe(db_ => {
      if (db_) {
        this.bus.db.next(db_);
      } else {
        this.bus.db.next(null);
      }
    });
  }

  ngOnInit(): void {
  }

}
