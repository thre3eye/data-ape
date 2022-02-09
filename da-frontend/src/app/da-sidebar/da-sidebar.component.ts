import { Component, OnInit } from '@angular/core';
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
  public isLoading = true;

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
    this.mongoDb.getTables(this.db).subscribe(tables_ => {
      if (!tables_)
        return;
      this.tables = tables_;
    });
  }

  ngOnInit(): void {
    this.mongoDb.getDatabase().subscribe(db_ => {
      this.db = db_.dbName;
      this.refresh();
    });
    this.mongoDb.isLoading.subscribe(val_ => this.isLoading = val_);
  }

}
