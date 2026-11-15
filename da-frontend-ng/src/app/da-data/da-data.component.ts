import { AfterViewInit, Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { environment } from 'src/environments/environment';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, isDocumentId, TableDescription } from '../services/da-mongo.service';

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
  public originalDocument?: string;
  public decimalDigits = 2;

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) { }

  public get canWrite(): boolean {
    return this.mongoDb.canWrite;
  }

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
    switch (action) {
      case 'delete':
        this.deleteRecord(obj['_id']);
        break;
      case 'view':
        if (this.canWrite && isDocumentId(obj['_id'])) {
          const current = this.data;
          this.mongoDb.getDocument(current.db, current.table, obj['_id']).subscribe(document => {
            if (document !== null && this.data === current) {
              this.selection = { table: current.table, idx: idx, data: obj };
              this.originalDocument = document;
              this.selectionText = JSON.stringify(JSON.parse(document), null, 2);
            }
          });
        } else {
          this.selection = { table: this.data.table, idx: idx, data: obj };
          this.originalDocument = undefined;
          this.selectionText = JSON.stringify(obj, null, 2);
        }
        break;
    }
    this.dataCtxMenu.nativeElement.style.display = "none";
  }

  public deleteRecord(id: unknown): void {
    if (!this.canWrite || !this.data || !isDocumentId(id)) {
      alert('Deleting requires a nonempty string or ObjectId _id and enabled writes.');
      return;
    }
    const current = this.data;
    if (confirm('Archive and delete this database record?')) {
      this.mongoDb.deleteRecord(current.db, current.table, id).subscribe({
        next: result => {
          if (result) {
            this.mongoDb.getTableData(current.db, current.table, this.mongoDb.getQueryParameters(current.table));
          } else {
            alert('Document was not found. Refresh and verify.');
          }
        },
        error: (error: HttpErrorResponse) => {
          const message = error.error?.msg;
          alert(typeof message === 'string' ? message : 'Delete failed or its outcome is unknown. Verify both collections.');
        }
      });
    }
  }

  public saveDataView(): void {
    if (!this.canWrite || !this.data || !this.originalDocument || !this.selectionText) {
      return;
    }
    try {
      JSON.parse(this.selectionText);
    } catch {
      alert('Invalid JSON');
      return;
    }
    const current = this.data;
    if (confirm('Replace this database record?')) {
      this.mongoDb.saveRecord(current.db, current.table, this.originalDocument, this.selectionText)
        .subscribe(result => {
          if (result) {
            this.closeDataView();
            this.mongoDb.getTableData(current.db, current.table, this.mongoDb.getQueryParameters(current.table));
          } else {
            alert('Save failed or the record changed. Refresh and try again.');
          }
        });
    }
  }

  public closeDataView(): void {
    this.selection = undefined;
    this.originalDocument = undefined;
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
      this.decimalDigits = this.mongoDb.getDecimalDigits();
      this.highlight = this.mongoDb.getQueryParameters(this.data.table).highlight;
    })
  }

  ngAfterViewInit(): void {
    this.log.log(`ngAfterViewInit: ${this.headerCtxMenu}`);
  }

}
