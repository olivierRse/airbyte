import React from "react";
import styles from "./Callout.module.css";

export const Callout = ({ className, children }) => {
  const calloutClassName = `${styles.callout} ${className}`;
  return <div className={calloutClassName}>{children}</div>;
};
