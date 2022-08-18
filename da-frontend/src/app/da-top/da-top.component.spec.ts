import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DaTopComponent } from './da-top.component';

describe('DaTopComponent', () => {
  let component: DaTopComponent;
  let fixture: ComponentFixture<DaTopComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ DaTopComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(DaTopComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
