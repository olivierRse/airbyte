import React from "react";
import styles from "./Chip.module.css";

export const Chip = ({ className, ...rest }) => {
  const chipClassName = `${styles.chip} ${className}`;
  return <span className={chipClassName} {...rest} />;
};
