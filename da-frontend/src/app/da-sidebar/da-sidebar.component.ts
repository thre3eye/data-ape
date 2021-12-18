import { Component, OnInit } from '@angular/core';
import { defer, Observable } from 'rxjs';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, Tables } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-sidebar',
  templateUrl: './da-sidebar.component.html',
  styleUrls: ['./da-sidebar.component.scss']
})
export class DaSidebarComponent implements OnInit {

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
    this.log.log(`Table: ${table_}`);
    this.mongoDb.getTableData(table_);//.subscribe(data_ => {
    //  });
  }

  ngOnInit(): void {
    this.mongoDb.getTables().subscribe(data_ => {
      if (!data_ || !data_.tables)
        return;
      this.tables = data_.tables;
    });
  }

}
