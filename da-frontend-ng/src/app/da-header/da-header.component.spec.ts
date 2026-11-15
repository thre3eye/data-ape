import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DaHeaderComponent } from './da-header.component';

describe('DaHeaderComponent', () => {
  let component: DaHeaderComponent;
  let fixture: ComponentFixture<DaHeaderComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ DaHeaderComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(DaHeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
