import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class DaLogService {

  constructor() { }

  public log(msg_: any): void {
    console.log(`${new Date()} : ${JSON.stringify(msg_)}`);
  }
  
}
