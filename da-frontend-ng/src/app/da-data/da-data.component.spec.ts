import { CommonModule } from '@angular/common';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { DaFormatPipe } from '../services/da-format.pipe';
import { DaMongoService } from '../services/da-mongo.service';
import { DaDataComponent } from './da-data.component';

describe('DaDataComponent read-only view', () => {
  let fixture: ComponentFixture<DaDataComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CommonModule],
      declarations: [DaDataComponent, DaFormatPipe],
      providers: [{ provide: DaMongoService, useValue: { data: new Subject() } }],
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
});
