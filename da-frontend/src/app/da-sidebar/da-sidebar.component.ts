import { Component, OnInit } from '@angular/core';
import { take } from 'rxjs';
import { DaBusService } from '../services/da-bus.service';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, Table } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-sidebar',
  templateUrl: './da-sidebar.component.html',
  styleUrls: ['./da-sidebar.component.scss']
})
export class DaSidebarComponent implements OnInit {

  public db?: string;
  public tables: Table[] = [];
  public isLoading = false;

  private isTopVisible = true;

  public selectedTable?: string;

  constructor(
    private log: DaLogService,
    private bus: DaBusService,
    private mongoDb: DaMongoService
  ) {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_ || !data_.table)
        return;
      this.selectedTable = data_.table;
    });
    this.bus.db.subscribe(db_ => {
      if (db_ == null)
        return;
      this.db = db_.dbName;
      this.tables = db_.tables;
    });
    this.bus.isTopVisible.subscribe(status_ => {
      this.isTopVisible = status_;
    });
  }

  public connect(): void {
    this.isTopVisible = !this.isTopVisible;
    this.bus.isTopVisible.next(this.isTopVisible);
  }

  public select(table_: Table): void {
    if (!this.db)
      return;
    this.log.log(`Table: ${table_}`);
    let params = this.mongoDb.getQueryParameters(table_.name);
    this.mongoDb.getTableData(this.db, table_.name, params);
  }

  public refresh(): void {
    if (!this.db)
      return;
    this.mongoDb.getTables(this.db);//.subscribe();//.pipe(take(1));//.subscribe();
    // this.mongoDb.getTables(this.db).subscribe(db_ => {
    //   this.bus.db.next(db_);
    //   // if (db_ == null || db_.tables == null)
    //   //   return;
    //   // this.db = db_.dbName;
    //   // this.tables = db_.tables;
    // });
  }

  ngOnInit(): void {
    // this.mongoDb.getDatabase().subscribe(db_ => {
    //   this.db = db_.dbName;
    //   this.refresh();
    // });
    this.mongoDb.isLoading.subscribe(
      val_ => this.isLoading = val_
    );
  }

}
