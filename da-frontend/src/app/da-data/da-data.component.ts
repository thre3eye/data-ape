import { AfterViewInit, Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { environment } from 'src/environments/environment';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, QueryParameters, TableDescription } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-data',
  templateUrl: './da-data.component.html',
  styleUrls: ['./da-data.component.scss']
})
export class DaDataComponent implements OnInit, AfterViewInit {

  @ViewChild('dataCtxMenu') dataCtxMenu?: ElementRef;
  @ViewChild('headerCtxMenu') headerCtxMenu?: ElementRef;

  public data?: TableDescription;
  public highlight?: string;
  public selection?: { table: string, idx: number, data: any };
  public selectionText?: string;

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) { }

  public onDragStart(event_: DragEvent, header_: string): void {
    this.log.log(`onDragStart: ${header_}`);
    event_.dataTransfer?.setData('string', header_);
  }

  public drop(event_: DragEvent): void {
    let srcKey = event_.dataTransfer?.getData('string');
    let dstKey = (<Element>event_.target).innerHTML;
    if (!srcKey || !dstKey)
      return;
    this.mongoDb.swapColumns(srcKey, dstKey);
  }

  public headerContextMenu(event_: MouseEvent, header_: string): void {
    if (!this.data || !this.headerCtxMenu)
      return;
    this.log.log(`headerContextMenu: ${header_}`);
    let ctxMenu = this.headerCtxMenu.nativeElement;
    ctxMenu.dataset.id = header_;
    ctxMenu.style.display = "block";
    ctxMenu.style.left = (event_.pageX - 10) + "px";
    ctxMenu.style.top = (event_.pageY - 10) + "px";
  }

  public headerContextMenuClick(event_: MouseEvent): void {
    if (!this.data || !this.headerCtxMenu)
      return;
    let action = (<HTMLElement>event_.target).dataset['action'];
    switch (action) {
      case 'hide':
        let hide = this.headerCtxMenu.nativeElement.dataset.id;
        this.mongoDb.hideColumn(hide);
        this.log.log(`headerContextMenuClick: ${action}/${hide}`);
        break;
      case 'view':
        let view = (<HTMLElement>event_.target).dataset['val'] ?? '';
        if ('__all__' === view) {
          this.mongoDb.viewColumn(undefined);
        } else {
          this.mongoDb.viewColumn(view);
        }
        this.log.log(`headerContextMenuClick: ${action}/${view}`);
        break;
    }
    this.headerCtxMenu.nativeElement.style.display = "none";
  }

  public dataContextMenu(event_: MouseEvent, idx_: number): void {
    if (!this.data || !this.dataCtxMenu)
      return;
    this.log.log(`dataContextMenu: ${idx_}`);
    let ctxMenu = this.dataCtxMenu.nativeElement;
    ctxMenu.dataset.id = idx_;
    ctxMenu.style.display = "block";
    ctxMenu.style.left = (event_.pageX - 10) + "px";
    ctxMenu.style.top = (event_.pageY - 10) + "px";
  }

  public dataContextMenuClick(event_: MouseEvent): void {
    if (!this.data || !this.dataCtxMenu)
      return;
    let action = (<HTMLElement>event_.target).dataset['action'];
    let idx = this.dataCtxMenu.nativeElement.dataset.id;
    let obj: { [key: string]: any } = {};
    this.data.headers.forEach((header_, headerIdx_) => {
      let col = this.data?.data[headerIdx_];
      if (!col)
        return;
      let value = col[idx];
      obj[header_] = value;
    });
    this.log.log(`dataContextMenuClick: ${action}/${idx}: ${JSON.stringify(obj)}`);
    switch (action) {
      case 'delete':
        let id = obj['_id'];
        this.deleteRecord(id);
        break;
      case 'view':
        this.selection = { table: this.data.table, idx: idx, data: obj };
        this.selectionText = JSON.stringify(obj, null, 2);
        break;
    }
    this.dataCtxMenu.nativeElement.style.display = "none";
  }

  public deleteRecord(id_: string): void {
    if (!id_ || !this.data)
      return;
    this.log.log(`deleteRecord: ${id_}`);
    if (confirm(`Delete database record '${id_}'?`)) {
      this.mongoDb.deleteRecord(this.data.db, this.data.table, id_).subscribe(result_ => {
        if (!result_) {
          alert(`Failed to delete record '${id_}'?`);
        } else if (this.data) {
          let params: QueryParameters = this.mongoDb.getQueryParameters(this.data.table);
          this.mongoDb.getTableData(this.data?.db, this.data?.table, params);
        }
      });
    }
  }

  public saveDataView(): void {
    if (!this.selectionText || !this.data)
      return;
    this.log.log(`data: ${this.selectionText}`);
    if (confirm(`Overwrite database record?`)) {
      try {
        let obj = JSON.parse(this.selectionText);
        this.log.log(`Saving: ${obj}`);
        this.mongoDb.saveRecord(this.data.db, this.data?.table, obj).subscribe(result_ => {
          if (result_ && this.data) {
            let params: QueryParameters = this.mongoDb.getQueryParameters(this.data.table);
            this.mongoDb.getTableData(this.data?.db, this.data?.table, params);
          }
        });
      } catch (ex) {
        alert(`Invalid JSON`);
      }
    }
  }

  public closeDataView(): void {
    this.selection = undefined;
  }


  public selectCell(val_: string): void {
    if (!this.data)
      return;
    if (!environment.production) {
      this.log.log(`selectCell: ${val_}`);
    }
    this.highlight = val_;
    this.mongoDb.getQueryParameters(this.data.table).highlight = this.highlight;
  }

  ngOnInit(): void {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_)
        return;
      //     this.viewIdx = undefined;
      this.data = data_;
      this.highlight = this.mongoDb.getQueryParameters(this.data.table).highlight;
    })
  }

  ngAfterViewInit(): void {
    this.log.log(`ngAfterViewInit: ${this.headerCtxMenu}`);
  }

}
