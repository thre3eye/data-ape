import { Directive, Input, TemplateRef, ViewContainerRef } from '@angular/core';

@Directive({
  selector: '[daVar]'
})
/**
 * Define variables in angular html containers/templates, can reference model: *daVar="{val:'hey!"} as param"
 */
export class DaVarDirective {

  @Input()
  set daVar(context: any) {
    this.context.$implicit = this.context.daVar = context;
    this.updateView();
  }

  context: any = {};

  constructor(private vcRef: ViewContainerRef, private templateRef: TemplateRef<any>) { }

  updateView() {
    this.vcRef.clear();
    this.vcRef.createEmbeddedView(this.templateRef, this.context);
  }

}
