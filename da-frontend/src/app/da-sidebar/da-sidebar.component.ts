import { Component, OnInit } from '@angular/core';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-sidebar',
  templateUrl: './da-sidebar.component.html',
  styleUrls: ['./da-sidebar.component.scss']
})
export class DaSidebarComponent implements OnInit {

  public db?: string;
  public tables: string[] = [];

  public selectedTable?: string;

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_ || !data_.table)
        return;
      this.selectedTable = data_.table;
    });
  }

  public select(table_: string): void {
    if (!this.db)
      return;
    this.log.log(`Table: ${table_}`);
    let params = this.mongoDb.getQueryParameters(table_);
    this.mongoDb.getTableData(this.db, table_, params);
  }

  ngOnInit(): void {
    this.mongoDb.getDatabase().subscribe(db_ => {
      this.db = db_.dbName;
      this.mongoDb.getTables(this.db).subscribe(data_ => {
        if (!data_ || !data_.tables)
          return;
        this.tables = data_.tables;
      });
    });
  }

}
