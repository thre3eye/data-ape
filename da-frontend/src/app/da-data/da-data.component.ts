import { AfterViewInit, Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { __spreadArrays } from 'tslib';
import { DaLogService } from '../services/da-log.service';
import { DaMongoService, TableDescription } from '../services/da-mongo.service';

@Component({
  selector: 'app-da-data',
  templateUrl: './da-data.component.html',
  styleUrls: ['./da-data.component.scss']
})
export class DaDataComponent implements OnInit, AfterViewInit {

  @ViewChild('ctxMenu') ctxMenu?: ElementRef;

  public data?: TableDescription;
  public size: number = 0;

  constructor(
    private log: DaLogService,
    private mongoDb: DaMongoService
  ) { }

  // public getData(i_: number, j_: number): any {
  //   if (!this.data || !this.data.data)
  //     return null;
  //   //   this.log.log(`index ${i_}/${j_}`);
  //   let value = this.data.data[i_][j_];
  //   return value;
  // }

  // public getStyleClass(i_: number): string {
  //   if (!this.data || !this.data.types)
  //     return '';
  //   let value = this.data?.types[i_];
  //   return value;
  // }

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

  // public getHeaders(): string[] {
  //   if (!this.data)
  //     return [];
  //   let headers: Set<string> = new Set(this.data.headers);
  //   return Array.from(headers);
  // }

  // public getHeaderIndices(): number[] {
  //   if (!this.data)
  //     return [];
  //   let indices: number[] = [];
  //   let headers = this.getHeaders();
  //   let bla = headers.forEach(h_ => {
  //     let idx = this.data?.headers.indexOf(h_) ?? -1;
  //     if (idx >= 0) {
  //       indices.push(idx);
  //     }
  //   });
  //   return indices;
  // }

  public contextMenu(event_: MouseEvent, header_: string): void {
    if (!this.data || !this.ctxMenu)
      return;
    this.log.log(`contextMenu: ${header_}`);
    let ctxMenu = this.ctxMenu.nativeElement;
    ctxMenu.dataset.id = header_;
    ctxMenu.style.display = "block";
    ctxMenu.style.left = (event_.pageX - 10) + "px";
    ctxMenu.style.top = (event_.pageY - 10) + "px";
  }

  public contextMenuClick(event_: MouseEvent): void {
    if (!this.data || !this.ctxMenu)
      return;
    let action = (<HTMLElement>event_.target).dataset['action'];
    switch (action) {
      case 'hide':
        let hide = this.ctxMenu.nativeElement.dataset.id;
        this.mongoDb.hideColumn(hide);
        //     this.hiddenCols.add(hide);
        this.log.log(`contextMenuClick: ${action}/${hide}`);
        break;
      case 'view':
        let view = (<HTMLElement>event_.target).dataset['val'] ?? '';
        if ('__all__' === view) {
          this.mongoDb.viewColumn(undefined);
        } else {
          this.mongoDb.viewColumn(view);
        }
        this.log.log(`contextMenuClick: ${action}/${view}`);
        break;
    }
    //    this.mongoDb.setHiddenColumns(this.data.table, this.hiddenCols);
    this.ctxMenu.nativeElement.style.display = "none";
  }

  public select(idx_: number): void {
    this.log.log(`idx: ${idx_}`);
  }

  ngOnInit(): void {
    this.mongoDb.data.subscribe(data_ => {
      if (!data_)
        return;
      this.data = data_;// JSON.stringify(data_.data);
      //     this.log.log(data_);
      //   this.headers = Object.getOwnPropertyNames(data_.data);// [...data_.data.keys()]
      //   this.size = (data_.data)[this.headers[0]].length;
      //   this.log.log(this.headers);
    })
  }

  ngAfterViewInit(): void {
    this.log.log(`ngAfterViewInit: ${this.ctxMenu}`);
  }

}
