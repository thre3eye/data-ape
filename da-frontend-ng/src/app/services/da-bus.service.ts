import { Injectable } from '@angular/core';
import { BehaviorSubject, Subject } from 'rxjs';
import { DatabaseDescription, Table } from './da-mongo.service';

@Injectable({
  providedIn: 'root'
})
export class DaBusService {

  public isTopVisible = new BehaviorSubject<boolean>(true);
  public db = new Subject<DatabaseDescription | null>();

  constructor() { }
}
