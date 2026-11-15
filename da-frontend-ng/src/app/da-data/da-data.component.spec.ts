import { CommonModule } from '@angular/common';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { DaFormatPipe } from '../services/da-format.pipe';
import { DaMongoService } from '../services/da-mongo.service';
import { DaDataComponent } from './da-data.component';

describe('DaDataComponent read-only view', () => {
  let fixture: ComponentFixture<DaDataComponent>;
  let mongo: { data: Subject<unknown>, canWrite: boolean };

  beforeEach(async () => {
    mongo = { data: new Subject(), canWrite: false };
    await TestBed.configureTestingModule({
      imports: [CommonModule, FormsModule],
      declarations: [DaDataComponent, DaFormatPipe],
      providers: [{ provide: DaMongoService, useValue: mongo }],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(DaDataComponent);
    fixture.componentInstance.selection = { table: 'sample', idx: 0, data: {} };
    fixture.componentInstance.selectionText = '{"value":1}';
    fixture.detectChanges();
  });

  it('allows viewing a record without exposing write actions', () => {
    const element: HTMLElement = fixture.nativeElement;
    const textarea = element.querySelector('textarea');
    expect(textarea?.readOnly).toBeTrue();
    expect(textarea?.value).toBe('{"value":1}');
    expect(element.querySelector('[data-action="view"]')).not.toBeNull();
    expect(element.querySelector('[data-action="delete"]')).toBeNull();
    expect(element.querySelector('[title="Save..."]')).toBeNull();
  });

  it('only exposes editing after a complete document is loaded and writes are enabled', () => {
    mongo.canWrite = true;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('textarea').readOnly).toBeTrue();
    expect(fixture.nativeElement.querySelector('[data-action="delete"]')).not.toBeNull();
    fixture.componentInstance.originalDocument = '{"_id":"one"}';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('textarea').readOnly).toBeFalse();
    expect(fixture.nativeElement.querySelector('[title="Save..."]')).not.toBeNull();
  });
});
