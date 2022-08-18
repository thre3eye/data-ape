import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import { DaLogService } from './da-log.service';

@Injectable({
  providedIn: 'root'
})
export class DaMsgService {

  public messages: Subject<MessageData> = new Subject();

  constructor(private log: DaLogService) { }

  public publish(msg_: MessageData): void {
    this.messages.next(msg_);
    setTimeout(() => this.messages.next({ text: '', level: MessageLevel.Default }), 5000);
  }
}

export enum MessageLevel {
  Default = "default",
  Error = "error",
  Warn = "warn",
}

export interface MessageData {
  text: string;
  level: MessageLevel;
}
